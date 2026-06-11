//! JNI 桥接最小闭环（Task A2）：Kotlin ↔ Rust 双向调用 + protect 回调。
//!
//! 本模块实现任务文档 A2 的契约：
//!
//! ```text
//! Kotlin → Rust:
//!   nativeStart(tunFd: Int, configJson: String): Long   // 返回 handle（0=失败）
//!   nativeStop(handle: Long)
//!   nativeStats(handle: Long): String                    // A2 先返回 "{}"
//! Rust → Kotlin（回调）:
//!   protect(fd: Int): Boolean
//!   onStatus(state: String)
//! ```
//!
//! ## 设计要点（对齐任务文档 0.3 / A2）
//!
//! - **panic 隔离**：每个 `extern "C"` 函数体用 [`std::panic::catch_unwind`] 包裹，
//!   panic 时记日志并返回 0/false/空串，绝不让 panic 跨越 JNI 边界（铁律 0.3-4）。
//! - **handle 管理**：`nativeStart` 返回 `Box::into_raw` 的 [`CoreHandle`] 指针（转 i64），
//!   `nativeStop` 用 `Box::from_raw` 回收。不使用全局可变单例，以支持多次 start/stop
//!   （切换节点场景）。
//! - **回调机制**：保存 [`jni::JavaVM`] 与 `NativeBridge` 实例的 [`GlobalRef`]，
//!   任意线程回调时 `attach_current_thread` 拿到 `JNIEnv` 再 `call_method`。
//!
//! A2 仅打通桥接与回调闭环，**不含任何数据面逻辑**（TUN/栈/出站在 A3+ 接入）。
//! 为证明 protect 回调真实生效，`nativeStart` 在建立句柄后会立即触发一次 protect 回调
//! （传入 tun_fd），这与 A2 验收「spy 计数 ≥ 1」对齐；A3 起改由真实出站路径触发。

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde::Deserialize;

use crate::error::{CoreError, Result};

/// 节点与运行时配置（由 Kotlin 侧 `configJson` 下发）。
///
/// A2 阶段仅解析最小字段，证明 JSON 通路可用；A5/B6 会扩充节点地址、密钥、cipher、
/// 路由规则等。所有字段都给出默认值，保证旧/简化配置仍能解析（向前兼容）。
#[derive(Debug, Clone, Deserialize)]
pub struct AndroidConfig {
    /// TUN 的 MTU，默认 1500（对齐桌面与 A3 Builder 配置）。
    #[serde(default = "default_mtu")]
    pub mtu: usize,

    /// remote 节点地址（A5 起使用；A2 允许缺省）。
    #[serde(default)]
    pub remote_host: String,

    /// remote 节点端口（A5 起使用；A2 允许缺省）。
    #[serde(default)]
    pub remote_port: u16,
}

fn default_mtu() -> usize {
    1500
}

impl AndroidConfig {
    /// 从 `configJson` 字符串解析配置。
    ///
    /// 空串视为「全部使用默认值」，便于 A2 阶段用最简配置完成闭环验证。
    pub fn from_json(json: &str) -> Result<Self> {
        let trimmed = json.trim();
        if trimmed.is_empty() {
            return Ok(Self {
                mtu: default_mtu(),
                remote_host: String::new(),
                remote_port: 0,
            });
        }
        Ok(serde_json::from_str(trimmed)?)
    }
}

/// Rust → Kotlin 回调上下文：持有跨线程回调所需的 `JavaVM` 与 bridge 全局引用。
///
/// 单独抽出并以 [`std::sync::Arc`] 共享，使其可被 clone 进任意 tokio worker 线程
/// 的 spawn 闭包中执行回调。`JavaVM` 与 [`GlobalRef`] 均为 `Send + Sync`，跨线程安全。
pub struct CallbackCtx {
    /// 进程级 `JavaVM`，用于在任意 Rust 线程 attach 后回调 Kotlin。
    vm: JavaVM,
    /// `NativeBridge` 实例的全局引用，回调 `protect` / `onStatus` 的接收者。
    bridge: GlobalRef,
}

impl CallbackCtx {
    /// 通过 `JavaVM` attach 当前线程，回调 Kotlin 侧 `NativeBridge.protect(fd)`。
    ///
    /// 这是「Rust → Kotlin」回调的核心：任意 tokio worker 线程都可调用本方法
    /// 把出站 socket fd 交给 `VpnService.protect` 排除出 TUN（防回环，铁律 0.3-1）。
    ///
    /// 返回 `Ok(true)` 表示 Kotlin 侧 protect 成功。
    pub fn protect(&self, fd: i32) -> Result<bool> {
        let mut env = self.vm.attach_current_thread()?;
        let ret = env.call_method(self.bridge.as_obj(), "protect", "(I)Z", &[JValue::Int(fd)])?;
        Ok(ret.z()?)
    }

    /// 回调 Kotlin 侧 `NativeBridge.onStatus(state)` 上报状态（best-effort）。
    pub fn on_status(&self, state: &str) -> Result<()> {
        let mut env = self.vm.attach_current_thread()?;
        let jstr = env.new_string(state)?;
        env.call_method(
            self.bridge.as_obj(),
            "onStatus",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&jstr)],
        )?;
        Ok(())
    }
}

/// 一次 VPN 会话的运行时上下文。
///
/// 由 `nativeStart` 创建并 `Box::into_raw` 交给 Kotlin 持有（以 i64 handle 形式），
/// `nativeStop` 时 `Box::from_raw` 回收。Drop 时关闭 tokio 运行时并发出 shutdown 信号。
pub struct CoreHandle {
    /// 跨线程回调上下文（protect / onStatus），可 clone 进 spawn 闭包。
    cb: std::sync::Arc<CallbackCtx>,
    /// 数据面 tokio 运行时（A2 用于在 worker 线程验证 protect 回调，A3+ 跑栈与出站）。
    rt: tokio::runtime::Runtime,
    /// 关停信号发送端，`nativeStop`/Drop 时置 true 通知数据面任务退出。
    shutdown: tokio::sync::watch::Sender<bool>,
    /// 解析后的配置（A3+ 使用）。
    #[allow(dead_code)]
    config: AndroidConfig,
}

impl CoreHandle {
    /// 在 tokio worker 线程（非 JNI 主线程）上触发一次 protect 回调。
    ///
    /// 用于 A2 闭环验证「Rust 任意线程 → 回调 Kotlin protect」。回调结果仅记录日志，
    /// 不阻断启动流程。
    fn spawn_initial_protect(&self, fd: i32) {
        let cb = std::sync::Arc::clone(&self.cb);
        self.rt.spawn(async move {
            match cb.protect(fd) {
                Ok(ok) => tracing::info!(fd, ok, "worker 线程 protect 回调完成"),
                Err(e) => tracing::warn!(error = %e, "worker 线程 protect 回调失败"),
            }
        });
    }
}

impl Drop for CoreHandle {
    fn drop(&mut self) {
        // 通知数据面任务退出；忽略接收端已全部关闭的错误。
        let _ = self.shutdown.send(true);
        tracing::debug!("CoreHandle dropped, shutdown signalled");
        // rt 在字段 drop 顺序中随后被回收（Runtime::drop 完成线程回收）。
    }
}

/// 把可能 panic 的闭包执行结果归一为 `jlong`：成功返回句柄值，失败/ panic 返回 0。
///
/// 任务文档 A2 测试要求：「闭包 panic 时返回 0，正常返回非 0」。
/// 这是 `nativeStart` 的 panic 防火墙（铁律 0.3-4）。
pub(crate) fn catch_unwind_to_jlong<F>(f: F) -> jlong
where
    F: FnOnce() -> Result<jlong>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => {
            tracing::error!(error = %e, "nativeStart 失败");
            0
        }
        Err(_) => {
            tracing::error!("nativeStart 捕获到 panic，已隔离，返回 0");
            0
        }
    }
}

/// 把可能 panic 的闭包执行结果归一为 `()`：失败/ panic 仅记录日志。
///
/// 用于 `nativeStop` 等无返回值且必须不 panic 跨 FFI 的入口。
pub(crate) fn catch_unwind_to_unit<F>(f: F)
where
    F: FnOnce() -> Result<()>,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(())) => {}
        Ok(Err(e)) => tracing::error!(error = %e, "JNI 调用失败"),
        Err(_) => tracing::error!("JNI 调用捕获到 panic，已隔离"),
    }
}

/// `nativeStart` 的纯逻辑实现（与 JNI 解耦，便于单测）。
///
/// 解析配置、保存 `JavaVM` 与 bridge 全局引用、构建运行时，组装成 [`CoreHandle`]
/// 并以裸指针（i64）返回。失败返回 [`CoreError`]，由外层 `catch_unwind_to_jlong`
/// 转成 0。
fn native_start_impl(
    env: &mut JNIEnv,
    this: &JObject,
    tun_fd: jint,
    config_json: &JString,
) -> Result<jlong> {
    if tun_fd < 0 {
        return Err(CoreError::InvalidArgument(format!(
            "tunFd 必须 >= 0，实际为 {tun_fd}"
        )));
    }

    let json: String = env.get_string(config_json)?.into();
    let config = AndroidConfig::from_json(&json)?;

    let vm = env.get_java_vm()?;
    let bridge = env.new_global_ref(this)?;

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    let (shutdown, _rx) = tokio::sync::watch::channel(false);

    let handle = Box::new(CoreHandle {
        cb: std::sync::Arc::new(CallbackCtx { vm, bridge }),
        rt,
        shutdown,
        config,
    });

    // A2 闭环验证：在 tokio worker 线程（**非 JNI 主线程**）上触发一次 protect 回调，
    // 证明「Rust 任意线程 → attach_current_thread → 回调 Kotlin protect」真实生效。
    // 这同时满足 A2 验收的两条：「非主线程能成功回调 protect」「spy 计数 ≥ 1」。
    // A3 起此处改为不主动触发，由真实出站路径在 connect 前调用。
    handle.spawn_initial_protect(tun_fd);

    Ok(Box::into_raw(handle) as jlong)
}

/// Kotlin → Rust：启动数据面会话，返回 handle（0 表示失败）。
///
/// # Safety
///
/// 由 JNI 运行时调用，`env` / `this` / `config_json` 均为 JVM 保证有效的引用。
/// 函数内部用 `catch_unwind` 隔离 panic，保证不跨越 FFI 边界。
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStart(
    mut env: JNIEnv,
    this: JObject,
    tun_fd: jint,
    config_json: JString,
) -> jlong {
    catch_unwind_to_jlong(|| native_start_impl(&mut env, &this, tun_fd, &config_json))
}

/// Kotlin → Rust：停止并回收一个 handle 对应的会话。
///
/// 传入 0 或已回收的 handle 是安全的（视为 no-op）。
///
/// # Safety
///
/// `handle` 必须是此前 `nativeStart` 返回且尚未被回收的有效值，或为 0。
/// 重复传入同一非 0 handle 属调用方错误（use-after-free），调用方（Kotlin）
/// 通过「回收后立即把 handle 置 0」来杜绝。
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStop(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    catch_unwind_to_unit(|| {
        if handle == 0 {
            return Ok(());
        }
        // SAFETY: handle 来自 nativeStart 的 Box::into_raw，且调用方保证不重复回收。
        let core = unsafe { Box::from_raw(handle as *mut CoreHandle) };
        let _ = core.shutdown.send(true);
        // 显式 drop 以触发运行时回收与日志（亦可隐式 drop）。
        drop(core);
        tracing::info!("nativeStop 完成，handle 已回收");
        Ok(())
    });
}

/// Kotlin → Rust：返回会话统计 JSON。A2 阶段固定返回 `"{}"`（B7 填充真实统计）。
///
/// # Safety
///
/// `handle` 必须是有效的 `nativeStart` 返回值或 0。本函数仅读取，不回收 handle。
#[no_mangle]
pub extern "C" fn Java_com_proxy_android_NativeBridge_nativeStats(
    env: JNIEnv,
    _this: JObject,
    _handle: jlong,
) -> jstring {
    // 注意：new_string 失败时返回 null，不 panic 跨 FFI。
    match env.new_string("{}") {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catch_unwind_returns_zero_on_panic() {
        let v = catch_unwind_to_jlong(|| panic!("boom"));
        assert_eq!(v, 0);
    }

    #[test]
    fn catch_unwind_returns_zero_on_err() {
        let v = catch_unwind_to_jlong(|| Err(CoreError::InvalidArgument("x".into())));
        assert_eq!(v, 0);
    }

    #[test]
    fn catch_unwind_returns_value_on_ok() {
        let v = catch_unwind_to_jlong(|| Ok(42));
        assert_eq!(v, 42);
    }

    #[test]
    fn catch_unwind_unit_swallows_panic() {
        // 不应 panic 逃逸；测试通过即代表被隔离。
        catch_unwind_to_unit(|| panic!("boom"));
        catch_unwind_to_unit(|| Err(CoreError::InvalidArgument("x".into())));
        catch_unwind_to_unit(|| Ok(()));
    }

    #[test]
    fn config_empty_uses_defaults() {
        let c = AndroidConfig::from_json("").expect("empty json should parse");
        assert_eq!(c.mtu, 1500);
        assert_eq!(c.remote_port, 0);
        assert!(c.remote_host.is_empty());
    }

    #[test]
    fn config_parses_fields() {
        let c =
            AndroidConfig::from_json(r#"{"mtu":1400,"remote_host":"1.2.3.4","remote_port":8443}"#)
                .expect("valid json should parse");
        assert_eq!(c.mtu, 1400);
        assert_eq!(c.remote_host, "1.2.3.4");
        assert_eq!(c.remote_port, 8443);
    }

    #[test]
    fn config_partial_json_fills_defaults() {
        let c = AndroidConfig::from_json(r#"{"remote_host":"example.com"}"#)
            .expect("partial json should parse");
        assert_eq!(c.mtu, 1500);
        assert_eq!(c.remote_host, "example.com");
    }

    #[test]
    fn config_invalid_json_errors() {
        let err = AndroidConfig::from_json("{not json}");
        assert!(err.is_err());
    }
}
