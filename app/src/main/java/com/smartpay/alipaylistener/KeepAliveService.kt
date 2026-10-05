package com.smartpay.alipaylistener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "alipay_listener_channel"
        private const val NOTIFICATION_ID = 1001
        // 静态实例，供MqttClientManager获取context诊断网络状态
        var instance: KeepAliveService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 注册网络状态监听，记录网络变化
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogManager.addLog("网络诊断", "网络已连接: $network")
            }
            override fun onLost(network: Network) {
                LogManager.addLog("网络诊断", "网络已断开: $network")
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝移动数据"
                    else -> "其他"
                }
                LogManager.addLog("网络诊断", "网络状态变化: $type")
            }
        }
        try {
            val req = NetworkRequest.Builder().build()
            cm.registerNetworkCallback(req, networkCallback)
            LogManager.addLog("网络诊断", "网络状态监听已注册")
        } catch (e: Exception) {
            LogManager.addLog("网络诊断", "注册网络监听失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pairCode = intent?.getStringExtra("pair_code") ?: ""
        if (pairCode.isNotEmpty()) {
            // 在后台线程连接MQTT
            Thread {
                MqttClientManager.connect(pairCode)
            }.start()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        MqttClientManager.disconnect()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "支付宝收款监听",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持支付宝收款监听服务运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("支付宝收款监听运行中")
            .setContentText("正在监听支付宝收款通知")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}

