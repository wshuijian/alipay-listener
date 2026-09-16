package com.smartpay.alipaylistener

import android.app.NotificationManager
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
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etPairCode: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOpenNotificationAccess: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val PREFS_NAME = "alipay_listener_prefs"
    private val KEY_PAIR_CODE = "pair_code"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPairCode = findViewById(R.id.et_pair_code)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnOpenNotificationAccess = findViewById(R.id.btn_open_notification_access)
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)

        // 加载保存的配对码
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etPairCode.setText(prefs.getString(KEY_PAIR_CODE, ""))

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
            append(if (isNotificationEnabled) "✅ 已开启" else "❌ 未开启（请先开启）")
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
}
