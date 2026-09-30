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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private lateinit var etPairCode: EditText
    private lateinit var btnStart: Button
    private lateinit var btnOpenNotificationAccess: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvStatusTop: TextView

    private val PREFS_NAME = "alipay_listener_prefs"
    private val KEY_PAIR_CODE = "pair_code"
    private val CHANNEL_ID_TEST = "test_channel"
    private val NOTIFICATION_ID_TEST = 9999

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPairCode = findViewById(R.id.et_pair_code)
        btnStart = findViewById(R.id.btn_start)
        btnOpenNotificationAccess = findViewById(R.id.btn_open_notification_access)
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)
        tvStatusTop = findViewById(R.id.tv_status_top)

        // 加载保存的配对码
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etPairCode.setText(prefs.getString(KEY_PAIR_CODE, ""))

        // 自动启动云音箱MVP前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, SpeakerService::class.java))
        } else {
            startService(Intent(this, SpeakerService::class.java))
        }

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

        // 打开通知监听权限设置
        btnOpenNotificationAccess.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
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

        if (MqttClientManager.isConnected() && MqttClientManager.isPaired()) {
            tvStatusTop.text = "已连接"
            tvStatusTop.setTextColor(0xFF22C55E.toInt())
        } else {
            tvStatusTop.text = "等待配对"
            tvStatusTop.setTextColor(0xFFF59E0B.toInt())
        }

        val status = buildString {
            append("通知权限: ").append(if (isNotificationEnabled) "已开启" else "未开启").append(" · ")
            append("云端: ").append(if (MqttClientManager.isConnected()) "已连接" else "未连接").append(" · ")
            append("配对码: ").append(pairCode)
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
}
