package com.proxy.android

import android.content.Context

/**
 * Android 端 VPN 节点配置。
 *
 * 目前 plane-core 只支持直连一个 proxy-remote 节点；后续接入多节点/路由后，
 * 这里可以自然扩展为列表与规则配置。
 */
data class AndroidVpnConfig(
    val remoteHost: String,
    val remotePort: Int,
    val remoteKey: String,
    val cipher: String,
    val tls: Boolean,
)

object VpnConfigStore {
    const val DEFAULT_REMOTE_HOST = "54.234.196.30"
    const val DEFAULT_REMOTE_PORT = 9090
    const val DEFAULT_REMOTE_KEY = "your-cipher-key"
    const val DEFAULT_CIPHER = "chacha20"
    const val DEFAULT_TLS = false

    private const val PREFS_NAME = "plane_vpn_config"
    private const val KEY_REMOTE_HOST = "remote_host"
    private const val KEY_REMOTE_PORT = "remote_port"
    private const val KEY_REMOTE_KEY = "remote_key"
    private const val KEY_CIPHER = "cipher"
    private const val KEY_TLS = "tls"

    fun load(context: Context): AndroidVpnConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AndroidVpnConfig(
            remoteHost = prefs.getString(KEY_REMOTE_HOST, DEFAULT_REMOTE_HOST) ?: DEFAULT_REMOTE_HOST,
            remotePort = prefs.getInt(KEY_REMOTE_PORT, DEFAULT_REMOTE_PORT),
            remoteKey = prefs.getString(KEY_REMOTE_KEY, DEFAULT_REMOTE_KEY) ?: DEFAULT_REMOTE_KEY,
            cipher = prefs.getString(KEY_CIPHER, DEFAULT_CIPHER) ?: DEFAULT_CIPHER,
            tls = prefs.getBoolean(KEY_TLS, DEFAULT_TLS),
        )
    }

    fun save(context: Context, config: AndroidVpnConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REMOTE_HOST, config.remoteHost)
            .putInt(KEY_REMOTE_PORT, config.remotePort)
            .putString(KEY_REMOTE_KEY, config.remoteKey)
            .putString(KEY_CIPHER, config.cipher)
            .putBoolean(KEY_TLS, config.tls)
            .apply()
    }
}
