package com.smartpay.alipaylistener

import android.app.Activity
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class MainActivity : Activity() {

    private lateinit var etPairCode: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOpenNotificationAccess: Button
    private lateinit var btnTestNotify: Button
    private lateinit var btnCopyLog: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val PREFS_NAME = "alipay_listener_prefs"
    private val KEY_PAIR_CODE = "pair_code"
    private val CHANNEL_ID_TEST = "test_channel"
    private val NOTIFICATION_ID_TEST = 9999

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPairCode = findViewById(R.id.et_pair_code)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnOpenNotificationAccess = findViewById(R.id.btn_open_notification_access)
        btnTestNotify = findViewById(R.id.btn_test_notify)
        btnCopyLog = findViewById(R.id.btn_copy_log)
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)

        // 加载保存的配对码
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etPairCode.setText(prefs.getString(KEY_PAIR_CODE, ""))

        // 自动启动云音箱MVP服务
        startService(Intent(this, SpeakerService::class.java))

        // 开始监听
        btnStart.setOnClickListener {
            val pairCode = etPairCode.text.toString().trim()
            if (pairCode.length != 6) {
                Toast.makeText(this, "请输入6位配对码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 保存配对码
            prefs.edit().putString(KEY_PAIR_CODE, pairCode).apply()

            // 启动服务
            val intent = Intent(this, KeepAliveService::class.java).apply {
                putExtra("pair_code", pairCode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "监听服务已启动", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        // 停止监听
        btnStop.setOnClickListener {
            stopService(Intent(this, KeepAliveService::class.java))
            MqttClientManager.disconnect()
            Toast.makeText(this, "监听服务已停止", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        // 打开通知监听权限设置
        btnOpenNotificationAccess.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        // 测试通知按钮
        btnTestNotify.setOnClickListener {
            sendTestNotification()
            Toast.makeText(this, "已发送测试通知", Toast.LENGTH_SHORT).show()
        }

        // 复制日志按钮
        btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("日志", tvLog.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "日志已复制，直接粘贴发给我就行！", Toast.LENGTH_LONG).show()
        }

        updateStatus()

        // 注册日志回调
        LogManager.setLogCallback { log ->
            runOnUiThread {
                tvLog.append(log + "\n")
                val logText = tvLog.text.toString()
                if (logText.length > 5000) {
                    tvLog.text = logText.substring(logText.length - 5000)
                }
            }
        }

        // 注册状态回调
        MqttClientManager.setStatusCallback { status, message ->
            runOnUiThread {
                updateStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isNotificationEnabled = isNotificationServiceEnabled()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pairCode = prefs.getString(KEY_PAIR_CODE, "未设置")

        val status = buildString {
            append("通知监听权限: ")
            append(if (isNotificationEnabled) "✅ 已开启" else "❌ 未开启（必须）")
            append("\n")
            append("配对码: ")
            append(pairCode)
            append("\n")
            append("云端连接: ")
            append(if (MqttClientManager.isConnected()) "✅ 已连接" else "❌ 未连接")
            append("\n")
            append("配对状态: ")
            append(if (MqttClientManager.isPaired()) "✅ 已配对" else "⏳ 等待配对")
        }
        tvStatus.text = status
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 发送测试通知
     */
    private fun sendTestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_TEST,
                "测试通知",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_TEST)
            .setContentTitle("支付宝收款监听测试")
            .setContentText("你已成功收款 0.01 元")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_TEST, notification)

        LogManager.addLog("测试", "已发送测试通知")
    }
}
