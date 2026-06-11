//! 系统 TUN fd 的异步读写封装（Task A3）。
//!
//! Android 侧 `VpnService.establish().detachFd()` 得到一个 TUN 文件描述符，
//! 经 `nativeStart` 传入 Rust。本模块用 [`tokio::io::unix::AsyncFd`] 把这个裸 fd
//! 包装成**异步**读写半部，并暴露与桌面 `tun-adapter/src/tun_device.rs` **完全相同
//! 的方法签名**（`read(&mut self, &mut [u8])` / `write(&mut self, &[u8])` /
//! `write_all(&mut self, &[u8])`），使 A4 复用的 `stack.rs` 调用方零感知差异。
//!
//! ## 关键约束
//!
//! - **所有权移交**：`detachFd()` 后 Java 不再持有 fd 所有权，Rust 必须在 stop/Drop 时
//!   `close(fd)`，否则泄漏（任务文档 A3.1）。本模块用 [`Arc`] 引用计数 + [`Drop`]
//!   保证 fd 仅在读写两半部都释放后关闭一次。
//! - **非阻塞**：fd 设为 `O_NONBLOCK`，配合 `AsyncFd` 的就绪通知处理 `EAGAIN`。
//! - **全双工**：TUN fd 可同时读写，`TunReader` / `TunWriter` 各持同一 fd 的 `Arc`。
//!
//! 仅在 unix（含 Android，本机 macOS 亦可用于单测）下编译。

#![cfg(unix)]

use std::io;
use std::os::unix::io::RawFd;
use std::sync::Arc;

use tokio::io::unix::AsyncFd;

/// 拥有 TUN fd 所有权的 RAII 包装：唯一负责在最后一个引用释放时 `close(fd)`。
///
/// `AsyncFd<RawFd>` 本身不会在 drop 时关闭底层 fd（它只管理就绪状态注册），
/// 因此需要本结构显式 close，避免 fd 泄漏（A3 验收：`/proc/self/fd` 计数稳定）。
struct OwnedTunFd {
    inner: AsyncFd<RawFd>,
}

impl OwnedTunFd {
    fn raw(&self) -> RawFd {
        *self.inner.get_ref()
    }
}

impl Drop for OwnedTunFd {
    fn drop(&mut self) {
        let fd = self.raw();
        // SAFETY: fd 由 establish()/detachFd() 移交所有权给本结构，且 OwnedTunFd 唯一，
        // 仅在此处 close 一次。close 失败仅记录日志（不 panic 跨 FFI）。
        let ret = unsafe { libc::close(fd) };
        if ret != 0 {
            tracing::warn!(
                fd,
                errno = io::Error::last_os_error().raw_os_error(),
                "close TUN fd 失败"
            );
        } else {
            tracing::debug!(fd, "TUN fd 已关闭");
        }
    }
}

/// 系统 TUN fd 的异步包装，提供 [`split`](AndroidTun::split) 得到读写两半部。
pub struct AndroidTun {
    fd: Arc<OwnedTunFd>,
    mtu: usize,
}

impl AndroidTun {
    /// 从裸 TUN fd 构造。
    ///
    /// 会把 fd 设为非阻塞（`O_NONBLOCK`）并注册到 tokio 反应堆。
    ///
    /// # Arguments
    ///
    /// - `fd`：`VpnService.establish().detachFd()` 得到的有效 TUN fd。
    /// - `mtu`：读缓冲大小依据（通常 1500），调用方按需用于分配缓冲。
    ///
    /// # Safety
    ///
    /// `fd` 必须是 `establish()` / `detachFd()` 得到的有效、独占的 TUN fd，
    /// **所有权移交给本结构**——调用方此后不得再 `close(fd)` 或在别处使用它。
    /// 传入无效 fd 会导致后续读写未定义。
    pub unsafe fn from_raw_fd(fd: RawFd, mtu: usize) -> io::Result<Self> {
        set_nonblocking(fd)?;
        let inner = AsyncFd::new(fd)?;
        Ok(Self {
            fd: Arc::new(OwnedTunFd { inner }),
            mtu,
        })
    }

    /// 本 TUN 的 MTU。
    pub fn mtu(&self) -> usize {
        self.mtu
    }

    /// 分离为读写两半部，供并发读/写使用。
    ///
    /// 两半部各持同一 fd 的 `Arc`，fd 在两者都被 drop 后由 [`OwnedTunFd`] 关闭一次。
    /// 签名与桌面 `TunManager::split` 的 `(TunReader, TunWriter)` 部分对齐
    /// （桌面额外返回 RouteGuard，Android 无需路由守卫）。
    pub fn split(self) -> (TunReader, TunWriter) {
        let r = TunReader {
            fd: Arc::clone(&self.fd),
        };
        let w = TunWriter { fd: self.fd };
        (r, w)
    }
}

/// TUN 读端。签名与桌面 `tun_device::TunReader` 一致，`stack.rs` 可直接复用。
pub struct TunReader {
    fd: Arc<OwnedTunFd>,
}

impl TunReader {
    /// 从 TUN 读取一个 IP 包（最多 `buf.len()` 字节，通常按 MTU 分配）。
    ///
    /// 内部处理 `EAGAIN`：fd 未就绪时由 `AsyncFd::readable` 异步等待，不忙等。
    pub async fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            let mut guard = self.fd.inner.readable().await?;
            let raw = self.fd.raw();
            match guard.try_io(|_inner| {
                // SAFETY: raw 是本结构持有的有效 fd；buf 为调用方提供的可写缓冲。
                let n =
                    unsafe { libc::read(raw, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
                if n < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(res) => return res,
                // would-block：清除就绪状态后重试 readable()。
                Err(_would_block) => continue,
            }
        }
    }
}

/// TUN 写端。签名与桌面 `tun_device::TunWriter` 一致，`stack.rs` 可直接复用。
pub struct TunWriter {
    fd: Arc<OwnedTunFd>,
}

impl TunWriter {
    /// 向 TUN 写入一个 IP 包，返回实际写入字节数。
    pub async fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        loop {
            let mut guard = self.fd.inner.writable().await?;
            let raw = self.fd.raw();
            match guard.try_io(|_inner| {
                // SAFETY: raw 是本结构持有的有效 fd；buf 为调用方提供的可读缓冲。
                let n = unsafe { libc::write(raw, buf.as_ptr() as *const libc::c_void, buf.len()) };
                if n < 0 {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(res) => return res,
                Err(_would_block) => continue,
            }
        }
    }

    /// 向 TUN 写入整个缓冲（循环直到全部写出）。
    ///
    /// 与桌面同名方法语义一致：`stack.rs` 用它写回 DNS 响应与下行 IP 包。
    pub async fn write_all(&mut self, buf: &[u8]) -> io::Result<()> {
        let mut written = 0;
        while written < buf.len() {
            let n = self.write(&buf[written..]).await?;
            if n == 0 {
                return Err(io::Error::new(io::ErrorKind::WriteZero, "TUN write 返回 0"));
            }
            written += n;
        }
        Ok(())
    }
}

/// 把 fd 设为非阻塞模式（`O_NONBLOCK`），`AsyncFd` 要求底层 fd 非阻塞。
fn set_nonblocking(fd: RawFd) -> io::Result<()> {
    // SAFETY: fcntl 对有效 fd 的标准用法。
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: 同上，写回带 O_NONBLOCK 的标志位。
    let ret = unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
    if ret < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 用 `socketpair` 造一对全双工 fd 模拟 TUN（不依赖真机）。
    /// 返回 (a, b)，写入 a 可从 b 读到，反之亦然。
    fn make_socketpair() -> (RawFd, RawFd) {
        let mut fds = [0 as RawFd; 2];
        // SAFETY: 标准 socketpair 调用，fds 长度为 2。
        let ret =
            unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, fds.as_mut_ptr()) };
        assert_eq!(ret, 0, "socketpair failed: {}", io::Error::last_os_error());
        (fds[0], fds[1])
    }

    #[tokio::test]
    async fn read_write_roundtrip() {
        let (a, b) = make_socketpair();

        // a 端用 AndroidTun 包装；b 端作为「对端」用阻塞 libc 读写做断言。
        // SAFETY: a 是 socketpair 产生的有效 fd，所有权移交 AndroidTun。
        let tun = unsafe { AndroidTun::from_raw_fd(a, 1500) }.expect("wrap fd");
        assert_eq!(tun.mtu(), 1500);
        let (mut reader, mut writer) = tun.split();

        // TunWriter::write_all → 对端 b 能读到相同字节。
        let payload = b"hello-tun-packet";
        writer.write_all(payload).await.expect("write_all");

        let mut recv = [0u8; 64];
        // SAFETY: b 有效，recv 可写。
        let n = unsafe { libc::read(b, recv.as_mut_ptr() as *mut libc::c_void, recv.len()) };
        assert!(n > 0, "peer read failed: {}", io::Error::last_os_error());
        assert_eq!(&recv[..n as usize], payload);

        // 对端 b 写入 → TunReader::read 能读回相同字节。
        let reply = b"reply-from-peer";
        // SAFETY: b 有效，reply 可读。
        let wn = unsafe { libc::write(b, reply.as_ptr() as *const libc::c_void, reply.len()) };
        assert_eq!(wn as usize, reply.len());

        let mut buf = [0u8; 64];
        let rn = reader.read(&mut buf).await.expect("read");
        assert_eq!(&buf[..rn], reply);

        // 清理对端 fd（a 端由 AndroidTun Drop 负责）。
        // SAFETY: b 仅在此处关闭一次。
        unsafe { libc::close(b) };
    }
}
