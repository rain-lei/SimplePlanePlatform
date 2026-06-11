package com.proxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 系统 VPN 服务。
 *
 * 进度：
 *
 * - A1：最小骨架——声明清单、被系统拉起并进入前台，但**不建立 TUN、不启动数据面**。
 * - A2：持有 [NativeBridge]，对接 Rust → Kotlin 的 `protect` / `onStatus` 回调。
 *   `protect(fd)` 复用 [VpnService] 自带方法，把出站 socket 排除出 TUN（防回环 0.3-1）。
 *
 * 真正的 `establish()` + `nativeStart(fd, configJson)` 仍在 Task A3/A6 接入；A2 不在
 * `onStartCommand` 里启动数据面，以免破坏 A1 已通过的生命周期验收。
 */
class PlaneVpnService : VpnService() {

    /**
     * JNI 桥接实例，关联本服务以便 Rust 回调 `protect` / `onStatus`。
     * A3/A6 起用它调用 `nativeStart` / `nativeStop`。
     */
    private val bridge: NativeBridge by lazy { NativeBridge(this) }

    /** 当前会话 handle（0 = 未启动）。A3/A6 起由 `nativeStart` 赋值。 */
    private var nativeHandle: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForegroundCompat()
        // A1/A2：不做数据面。A6 起在此处 buildTun() + bridge.nativeStart()。
        return START_STICKY
    }

    /**
     * 接收来自 Rust（经 [NativeBridge.onStatus]）的状态上报。
     *
     * A2 阶段仅记录日志；B5/B7 会据此刷新通知与 UI 状态。
     */
    fun onNativeStatus(state: String) {
        Log.i(TAG, "native status: $state")
    }

    /**
     * 用户在系统设置里撤销 VPN 授权时回调。A6 起须在此 nativeStop 并清理。
     */
    override fun onRevoke() {
        stopSelfSafely()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopSelfSafely()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        // 防御性回收：A2 不会 nativeStart，handle 恒为 0（no-op）；
        // A6 起 onStartCommand 会 nativeStart，届时此处确保 stop 时回收，杜绝 fd/连接泄漏。
        if (nativeHandle != 0L) {
            bridge.nativeStop(nativeHandle)
            nativeHandle = 0L
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_status_idle))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // targetSdk 34：前台服务必须指定类型，VPN 使用 specialUse。
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val TAG = "PlaneVpnService"
        private const val CHANNEL_ID = "plane_vpn_status"
        private const val NOTIFICATION_ID = 1001
    }
}
