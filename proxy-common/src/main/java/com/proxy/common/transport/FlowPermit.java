package com.proxy.common.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流量控制通行证 —— 背压机制的回调令牌
 * <p>
 * 由 BackpressureHandler 在每个 DATA 帧到达时创建，信用归还逻辑绑定为回调。
 * 令牌随数据在 pipeline 中流动，业务处理完成时调用 {@link #release()} 即可自动归还信用。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * // 同步处理
 * try { process(msg); } finally { permit.release(); }
 *
 * // 异步 CompletableFuture 链
 * CompletableFuture&lt;Void&gt; future = processAsync(msg);
 * permit.whenComplete(future);  // future 完成时自动 release
 * </pre>
 * </p>
 */
public final class FlowPermit {

    /**
     * 背压关闭时的零成本空实现，所有操作均为 no-op
     */
    public static final FlowPermit NOOP = new FlowPermit(null);

    private final Runnable onRelease;
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    FlowPermit(Runnable onRelease) {
        this.onRelease = onRelease;
    }

    /**
     * 归还此 permit 对应的信用额度。幂等，多次调用安全。
     */
    public void release() {
        if (onRelease != null && consumed.compareAndSet(false, true)) {
            onRelease.run();
        }
    }

    /**
     * 与 CompletableFuture 集成：future 完成时（无论成功/异常）自动 release。
     * <p>
     * 返回原始 future，方便链式调用：
     * <pre>
     * permit.whenComplete(invoker.invoke(invocation))
     *       .thenAccept(response -> writeResponse(ctx, response));
     * </pre>
     * </p>
     *
     * @param future 任意 CompletableFuture
     * @param <T>    future 的结果类型
     * @return 原始 future（非新实例，直接返回入参）
     */
    public <T> CompletableFuture<T> whenComplete(CompletableFuture<T> future) {
        future.whenComplete((r, e) -> this.release());
        return future;
    }

    /**
     * 是否已归还
     */
    public boolean isReleased() {
        return consumed.get();
    }

    /**
     * 合并多个 permit —— 调用 merged.release() 时，内部所有 permit 全部释放。
     * <p>
     * 用于 ProxyMessageDecoder 跨帧重组场景：N 个帧各消耗 1 个信用，
     * 对应的 N 个 permit 合并为一个，绑定到重组后的 ProxyMessage 上，
     * 消息处理完成后一次性归还所有信用。
     * </p>
     *
     * @param permits 要合并的 permit 列表（空列表返回 NOOP）
     * @return 合并后的单一 permit
     */
    public static FlowPermit merge(List<FlowPermit> permits) {
        if (permits == null || permits.isEmpty()) {
            return NOOP;
        }
        if (permits.size() == 1) {
            return permits.get(0);
        }
        // 防御性拷贝，避免外部修改列表
        final List<FlowPermit> copy = new ArrayList<>(permits);
        return new FlowPermit(() -> copy.forEach(FlowPermit::release));
    }
}
