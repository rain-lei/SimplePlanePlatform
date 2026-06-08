//! SOCKS5 客户端模块
//!
//! 实现轻量级 SOCKS5 客户端，将 TCP 字节流通过 SOCKS5 协议转发给 proxy-local。

use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::error::Socks5Error;

/// SOCKS5 认证握手超时时间
const AUTH_TIMEOUT: Duration = Duration::from_secs(5);
/// SOCKS5 CONNECT 响应超时时间
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// SOCKS5 版本号
const SOCKS5_VERSION: u8 = 0x05;
/// 无认证方式
const AUTH_NO_AUTH: u8 = 0x00;
/// CONNECT 命令
const CMD_CONNECT: u8 = 0x01;
/// 地址类型：IPv4
const ATYP_IPV4: u8 = 0x01;
/// 地址类型：域名
const ATYP_DOMAIN: u8 = 0x03;

/// 通过 SOCKS5 代理转发 TCP 流量
///
/// # Arguments
/// * `target` - 目标地址（域名或 IP 字符串）
/// * `port` - 目标端口
/// * `app_stream` - 应用层的 TCP 字节流（来自 smoltcp）
/// * `socks5_addr` - SOCKS5 代理地址（通常是 127.0.0.1:1080）
pub async fn proxy_tcp_stream<S>(
    target: &str,
    port: u16,
    app_stream: S,
    socks5_addr: SocketAddr,
) -> Result<(), Socks5Error>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    tracing::info!("SOCKS5 connecting to {}:{} via {}", target, port, socks5_addr);

    // 1. TCP connect 到 SOCKS5 代理
    let mut proxy_stream = tokio::time::timeout(AUTH_TIMEOUT, TcpStream::connect(socks5_addr))
        .await
        .map_err(|_| Socks5Error::Timeout)?
        .map_err(|_| Socks5Error::Unreachable(socks5_addr))?;

    // 2. SOCKS5 认证握手
    socks5_auth(&mut proxy_stream).await?;

    // 3. SOCKS5 CONNECT 请求
    socks5_connect_request(&mut proxy_stream, target, port).await?;

    // 4. 等待 SOCKS5 CONNECT 成功响应
    socks5_read_response(&mut proxy_stream).await?;

    tracing::info!("SOCKS5 tunnel established to {}:{}", target, port);

    // 5. 双向转发 — 使用分离的两个方向以便精确诊断哪侧先 EOF
    let (mut proxy_read, mut proxy_write) = tokio::io::split(proxy_stream);
    let (mut app_read, mut app_write) = tokio::io::split(app_stream);

    let target_a = format!("{}:{}", target, port);
    let target_b = target_a.clone();

    // app → proxy 方向（应用数据发往远端）
    let a2p = tokio::spawn(async move {
        let mut buf = vec![0u8; 32768];
        let mut total: u64 = 0;
        loop {
            let n = match tokio::time::timeout(
                Duration::from_secs(120),
                tokio::io::AsyncReadExt::read(&mut app_read, &mut buf),
            ).await {
                Ok(Ok(0)) => {
                    tracing::info!("[{}] app->proxy: app read EOF after {} bytes", target_a, total);
                    break;
                }
                Ok(Ok(n)) => n,
                Ok(Err(e)) => {
                    tracing::warn!("[{}] app->proxy: app read error after {} bytes: {}", target_a, total, e);
                    break;
                }
                Err(_) => {
                    tracing::warn!("[{}] app->proxy: app read timeout after {} bytes", target_a, total);
                    break;
                }
            };
            if total == 0 {
                tracing::info!("[{}] app->proxy: first chunk {} bytes", target_a, n);
            }
            total += n as u64;
            if let Err(e) = tokio::io::AsyncWriteExt::write_all(&mut proxy_write, &buf[..n]).await {
                tracing::warn!("[{}] app->proxy: proxy write error after {} bytes: {}", target_a, total, e);
                break;
            }
        }
        let _ = tokio::io::AsyncWriteExt::shutdown(&mut proxy_write).await;
        total
    });

    // proxy → app 方向（远端数据返回给应用）
    let p2a = tokio::spawn(async move {
        let mut buf = vec![0u8; 32768];
        let mut total: u64 = 0;
        loop {
            let n = match tokio::time::timeout(
                Duration::from_secs(120),
                tokio::io::AsyncReadExt::read(&mut proxy_read, &mut buf),
            ).await {
                Ok(Ok(0)) => {
                    tracing::info!("[{}] proxy->app: proxy read EOF after {} bytes", target_b, total);
                    break;
                }
                Ok(Ok(n)) => n,
                Ok(Err(e)) => {
                    tracing::warn!("[{}] proxy->app: proxy read error after {} bytes: {}", target_b, total, e);
                    break;
                }
                Err(_) => {
                    tracing::warn!("[{}] proxy->app: proxy read timeout after {} bytes", target_b, total);
                    break;
                }
            };
            if total == 0 {
                tracing::info!("[{}] proxy->app: first chunk {} bytes", target_b, n);
            }
            total += n as u64;
            if let Err(e) = tokio::io::AsyncWriteExt::write_all(&mut app_write, &buf[..n]).await {
                tracing::warn!("[{}] proxy->app: app write error after {} bytes: {}", target_b, total, e);
                break;
            }
        }
        let _ = tokio::io::AsyncWriteExt::shutdown(&mut app_write).await;
        total
    });

    let (sent, recv) = tokio::join!(a2p, p2a);
    let sent = sent.unwrap_or(0);
    let recv = recv.unwrap_or(0);
    tracing::info!("SOCKS5 tunnel closed: {}:{} (sent: {}, recv: {})", target, port, sent, recv);

    Ok(())
}

/// SOCKS5 认证握手（NO AUTH）
async fn socks5_auth(stream: &mut TcpStream) -> Result<(), Socks5Error> {
    // 发送: VER(0x05) NMETHODS(0x01) METHODS(0x00=NO_AUTH)
    let auth_request = [SOCKS5_VERSION, 0x01, AUTH_NO_AUTH];
    stream.write_all(&auth_request).await?;
    stream.flush().await?;

    // 读取响应: VER(0x05) METHOD(0x00)
    let mut response = [0u8; 2];
    tokio::time::timeout(AUTH_TIMEOUT, stream.read_exact(&mut response))
        .await
        .map_err(|_| Socks5Error::Timeout)??;

    if response[0] != SOCKS5_VERSION {
        return Err(Socks5Error::ProtocolError(format!(
            "invalid SOCKS5 version in auth response: {}",
            response[0]
        )));
    }

    if response[1] != AUTH_NO_AUTH {
        return Err(Socks5Error::AuthFailed);
    }

    Ok(())
}

/// 发送 SOCKS5 CONNECT 请求
async fn socks5_connect_request(
    stream: &mut TcpStream,
    target: &str,
    port: u16,
) -> Result<(), Socks5Error> {
    let mut request = Vec::new();
    request.push(SOCKS5_VERSION); // VER
    request.push(CMD_CONNECT); // CMD
    request.push(0x00); // RSV

    // 尝试解析为 IPv4 地址，否则作为域名发送
    if let Ok(ipv4) = target.parse::<std::net::Ipv4Addr>() {
        request.push(ATYP_IPV4);
        request.extend_from_slice(&ipv4.octets());
    } else {
        // 域名方式（推荐：让远端做 DNS 解析）
        request.push(ATYP_DOMAIN);
        let domain_bytes = target.as_bytes();
        if domain_bytes.len() > 255 {
            return Err(Socks5Error::ProtocolError("domain name too long".to_string()));
        }
        request.push(domain_bytes.len() as u8);
        request.extend_from_slice(domain_bytes);
    }

    // 端口（网络字节序，大端）
    request.extend_from_slice(&port.to_be_bytes());

    stream.write_all(&request).await?;
    stream.flush().await?;

    Ok(())
}

/// 读取 SOCKS5 CONNECT 响应
async fn socks5_read_response(stream: &mut TcpStream) -> Result<(), Socks5Error> {
    // 最小响应：VER(1) + REP(1) + RSV(1) + ATYP(1) + ADDR(variable) + PORT(2)
    let mut header = [0u8; 4];
    tokio::time::timeout(CONNECT_TIMEOUT, stream.read_exact(&mut header))
        .await
        .map_err(|_| Socks5Error::Timeout)??;

    if header[0] != SOCKS5_VERSION {
        return Err(Socks5Error::ProtocolError(format!(
            "invalid SOCKS5 version in connect response: {}",
            header[0]
        )));
    }

    // 检查响应码
    if header[1] != 0x00 {
        let reason = match header[1] {
            0x01 => "general SOCKS server failure",
            0x02 => "connection not allowed by ruleset",
            0x03 => "network unreachable",
            0x04 => "host unreachable",
            0x05 => "connection refused",
            0x06 => "TTL expired",
            0x07 => "command not supported",
            0x08 => "address type not supported",
            _ => "unknown error",
        };
        return Err(Socks5Error::ConnectRejected(format!(
            "reply code 0x{:02x}: {}",
            header[1], reason
        )));
    }

    // 读取并丢弃绑定地址信息
    match header[3] {
        ATYP_IPV4 => {
            // IPv4: 4 bytes addr + 2 bytes port
            let mut buf = [0u8; 6];
            stream.read_exact(&mut buf).await?;
        }
        ATYP_DOMAIN => {
            // Domain: 1 byte len + domain + 2 bytes port
            let mut len_buf = [0u8; 1];
            stream.read_exact(&mut len_buf).await?;
            let mut buf = vec![0u8; len_buf[0] as usize + 2];
            stream.read_exact(&mut buf).await?;
        }
        0x04 => {
            // IPv6: 16 bytes addr + 2 bytes port
            let mut buf = [0u8; 18];
            stream.read_exact(&mut buf).await?;
        }
        atyp => {
            return Err(Socks5Error::ProtocolError(format!(
                "unknown ATYP in response: 0x{:02x}",
                atyp
            )));
        }
    }

    Ok(())
}

/// 仅执行 SOCKS5 认证握手（用于健康检查）
pub async fn socks5_auth_handshake(stream: &mut TcpStream) -> Result<(), Socks5Error> {
    socks5_auth(stream).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;
    use tokio::net::TcpListener;

    /// 简单的 mock SOCKS5 server，接受连接后做认证和 CONNECT 响应
    async fn mock_socks5_server(listener: TcpListener) {
        let (mut stream, _) = listener.accept().await.unwrap();

        // 读取认证请求
        let mut buf = [0u8; 3];
        stream.read_exact(&mut buf).await.unwrap();
        assert_eq!(buf[0], SOCKS5_VERSION);
        assert_eq!(buf[1], 0x01);
        assert_eq!(buf[2], AUTH_NO_AUTH);

        // 响应认证成功
        stream.write_all(&[SOCKS5_VERSION, AUTH_NO_AUTH]).await.unwrap();

        // 读取 CONNECT 请求头
        let mut header = [0u8; 4];
        stream.read_exact(&mut header).await.unwrap();
        assert_eq!(header[0], SOCKS5_VERSION);
        assert_eq!(header[1], CMD_CONNECT);

        // 读取地址
        match header[3] {
            ATYP_DOMAIN => {
                let mut len = [0u8; 1];
                stream.read_exact(&mut len).await.unwrap();
                let mut domain = vec![0u8; len[0] as usize];
                stream.read_exact(&mut domain).await.unwrap();
                let mut port = [0u8; 2];
                stream.read_exact(&mut port).await.unwrap();
            }
            ATYP_IPV4 => {
                let mut addr = [0u8; 6]; // 4 + 2
                stream.read_exact(&mut addr).await.unwrap();
            }
            _ => panic!("unexpected ATYP"),
        }

        // 发送 CONNECT 成功响应
        let response = [
            SOCKS5_VERSION, 0x00, 0x00, ATYP_IPV4,
            0x00, 0x00, 0x00, 0x00, // 绑定地址
            0x00, 0x00, // 绑定端口
        ];
        stream.write_all(&response).await.unwrap();

        // Echo 模式：将收到的数据原样返回
        let mut buf = vec![0u8; 4096];
        loop {
            match stream.read(&mut buf).await {
                Ok(0) => break,
                Ok(n) => {
                    stream.write_all(&buf[..n]).await.unwrap();
                }
                Err(_) => break,
            }
        }
    }

    #[tokio::test]
    async fn test_socks5_auth_handshake() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 3];
            stream.read_exact(&mut buf).await.unwrap();
            stream.write_all(&[0x05, 0x00]).await.unwrap();
        });

        let mut stream = TcpStream::connect(addr).await.unwrap();
        let result = socks5_auth_handshake(&mut stream).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_socks5_proxy_flow() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        // 启动 mock server
        tokio::spawn(mock_socks5_server(listener));

        // 创建一个 duplex stream 模拟应用层
        let (mut client, server_side) = duplex(4096);

        // 在后台通过 SOCKS5 转发
        let proxy_task = tokio::spawn(async move {
            proxy_tcp_stream("www.example.com", 80, server_side, addr).await
        });

        // 发送数据
        client.write_all(b"Hello, World!").await.unwrap();
        client.flush().await.unwrap();

        // 读取回显
        let mut buf = vec![0u8; 13];
        client.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, b"Hello, World!");

        // 关闭
        drop(client);
        let _ = proxy_task.await;
    }

    #[tokio::test]
    async fn test_socks5_unreachable() {
        // 连接一个不存在的端口
        let (_client, server_side) = duplex(4096);
        let addr: SocketAddr = "127.0.0.1:19999".parse().unwrap();

        let result = proxy_tcp_stream("www.example.com", 80, server_side, addr).await;
        assert!(result.is_err());
    }
}
