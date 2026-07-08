package com.proxy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 主界面。
 *
 * 当前职责：
 *  1. 显示 native（plane-core）版本号，证明 `.so` 已加载、JNI 可调用。
 *  2. 连接前走 [VpnService.prepare]：若未授权则拉起系统 VPN 授权框，
 *     授权通过后启动前台 [PlaneVpnService]。
 *  3. 接收 [PlaneVpnService] 的状态广播，驱动按钮与状态文本，保证 UI 不自行猜测
 *     Service 生命周期。
 *
 * 后续阶段（B6）会把界面扩展为节点/规则配置与统计展示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var toggleVpnButton: Button
    private lateinit var saveConfigButton: Button
    private lateinit var vpnStatusText: TextView
    private lateinit var remoteHostInput: EditText
    private lateinit var remotePortInput: EditText
    private lateinit var remoteKeyInput: EditText
    private lateinit var cipherInput: EditText
    private lateinit var tlsCheckBox: CheckBox

    /** Activity 只保存 UI 状态；真实生命周期以 PlaneVpnService 广播为准。 */
    private var vpnRunning: Boolean = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PlaneVpnService.ACTION_STATUS_CHANGED) return
            val running = intent.getBooleanExtra(PlaneVpnService.EXTRA_RUNNING, false)
            val message = intent.getStringExtra(PlaneVpnService.EXTRA_STATUS_MESSAGE)
            updateVpnUi(running, message)
        }
    }

    // 系统 VPN 授权对话框的结果回调：用户点「允许」后启动 VPN 服务。
    private val prepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            updateVpnUi(false, getString(R.string.vpn_status_idle))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val versionView = findViewById<TextView>(R.id.nativeVersionText)
        versionView.text = getString(R.string.native_version_label, readNativeVersion())

        vpnStatusText = findViewById(R.id.vpnStatusText)
        remoteHostInput = findViewById(R.id.remoteHostInput)
        remotePortInput = findViewById(R.id.remotePortInput)
        remoteKeyInput = findViewById(R.id.remoteKeyInput)
        cipherInput = findViewById(R.id.cipherInput)
        tlsCheckBox = findViewById(R.id.tlsCheckBox)
        saveConfigButton = findViewById<Button>(R.id.saveConfigButton).apply {
            setOnClickListener { saveCurrentConfig(showSavedToast = true) }
        }
        toggleVpnButton = findViewById<Button>(R.id.toggleVpnButton).apply {
            setOnClickListener { onToggleClicked() }
        }
        bindConfigToForm(VpnConfigStore.load(this))
        updateVpnUi(PlaneVpnService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(PlaneVpnService.ACTION_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateVpnUi(PlaneVpnService.isRunning)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onStop()
    }

    private fun readNativeVersion(): String =
        runCatching { NativeBridge().nativeVersion() }
            .getOrElse { "<load failed: ${it.message}>" }

    private fun onToggleClicked() {
        if (vpnRunning) {
            stopVpn()
            return
        }

        if (saveCurrentConfig(showSavedToast = false) == null) return

        // prepare 返回非 null Intent 表示尚未授权，需要拉起系统授权框；
        // 返回 null 表示已授权，可直接启动。
        val intent: Intent? = VpnService.prepare(this)
        if (intent != null) {
            prepareLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val config = saveCurrentConfig(showSavedToast = false) ?: return
        val intent = Intent(this, PlaneVpnService::class.java)
            .setAction(PlaneVpnService.ACTION_START)
            .putExtra(PlaneVpnService.EXTRA_REMOTE_HOST, config.remoteHost)
            .putExtra(PlaneVpnService.EXTRA_REMOTE_PORT, config.remotePort)
            .putExtra(PlaneVpnService.EXTRA_REMOTE_KEY, config.remoteKey)
            .putExtra(PlaneVpnService.EXTRA_CIPHER, config.cipher)
            .putExtra(PlaneVpnService.EXTRA_TLS, config.tls)
        ContextCompat.startForegroundService(this, intent)
        updateVpnUi(true, getString(R.string.vpn_status_connecting))
    }

    private fun stopVpn() {
        val intent = Intent(this, PlaneVpnService::class.java)
            .setAction(PlaneVpnService.ACTION_STOP)
        // 用户正在前台操作时使用普通 startService 即可，避免 stop action 也触发
        // startForegroundService 的 5 秒前台通知要求。
        startService(intent)
        updateVpnUi(false, getString(R.string.vpn_status_disconnecting))
    }

    private fun bindConfigToForm(config: AndroidVpnConfig) {
        remoteHostInput.setText(config.remoteHost)
        remotePortInput.setText(config.remotePort.toString())
        remoteKeyInput.setText(config.remoteKey)
        cipherInput.setText(config.cipher)
        tlsCheckBox.isChecked = config.tls
    }

    private fun saveCurrentConfig(showSavedToast: Boolean): AndroidVpnConfig? {
        val config = readConfigFromForm() ?: return null
        VpnConfigStore.save(this, config)
        if (showSavedToast) {
            Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
        }
        return config
    }

    private fun readConfigFromForm(): AndroidVpnConfig? {
        remoteHostInput.error = null
        remotePortInput.error = null
        remoteKeyInput.error = null

        val host = remoteHostInput.text.toString().trim()
        if (host.isBlank()) {
            remoteHostInput.error = getString(R.string.config_error_host)
            remoteHostInput.requestFocus()
            return null
        }

        val port = remotePortInput.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            remotePortInput.error = getString(R.string.config_error_port)
            remotePortInput.requestFocus()
            return null
        }

        val key = remoteKeyInput.text.toString()
        if (key.isBlank()) {
            remoteKeyInput.error = getString(R.string.config_error_key)
            remoteKeyInput.requestFocus()
            return null
        }

        val tls = tlsCheckBox.isChecked
        if (tls) {
            Toast.makeText(this, R.string.config_error_tls_unsupported, Toast.LENGTH_LONG).show()
            tlsCheckBox.requestFocus()
            return null
        }

        return AndroidVpnConfig(
            remoteHost = host,
            remotePort = port,
            remoteKey = key,
            cipher = VpnConfigStore.DEFAULT_CIPHER,
            tls = tls,
        )
    }

    private fun updateVpnUi(
        running: Boolean,
        message: String? = null,
    ) {
        vpnRunning = running
        toggleVpnButton.text = getString(
            if (running) R.string.disconnect_vpn else R.string.connect_vpn,
        )
        vpnStatusText.text = message ?: getString(
            if (running) R.string.vpn_status_connected else R.string.vpn_status_idle,
        )
        setConfigInputsEnabled(!running)
    }

    private fun setConfigInputsEnabled(enabled: Boolean) {
        remoteHostInput.isEnabled = enabled
        remotePortInput.isEnabled = enabled
        remoteKeyInput.isEnabled = enabled
        saveConfigButton.isEnabled = enabled
        tlsCheckBox.isEnabled = enabled
        cipherInput.isEnabled = false
    }
}
