package com.smartpay.alipaylistener

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 云音箱秒播MVP服务 - 修复MQTT回调问题
 */
class SpeakerService : Service(), TextToSpeech.OnInitListener {
    private lateinit var mqttClient: MqttClient
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val playedMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val DEVICE_ID = "TEST-001"

    override fun onCreate() {
        super.onCreate()
        // 变成前台服务，避免被系统杀死
        val channelId = "speaker_service_channel"
        val channel = android.app.NotificationChannel(
            channelId,
            "云音箱服务",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("云音箱秒播服务运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1002, notification)

        tts = TextToSpeech(this, this)
        LogManager.addLog("Speaker服务", "Service已启动")
        initMqtt()
        loadHistoryPlayedIds()
    }

    private fun loadHistoryPlayedIds() {
        try {
            val db = openOrCreateDatabase("speaker_transactions.db", MODE_PRIVATE, null)
            db.execSQL("CREATE TABLE IF NOT EXISTS transactions (messageId TEXT PRIMARY KEY, deviceId TEXT, amount REAL, paymentType TEXT, message TEXT, receivedAt TEXT, playedAt TEXT, status TEXT)")
            val cursor = db.rawQuery("SELECT messageId FROM transactions WHERE status='PLAYED'", null)
            while (cursor.moveToNext()) {
                playedMessageIds.add(cursor.getString(0))
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {}
    }

    private fun initMqtt() {
        Thread {
            try {
                val broker = "tcp://broker.emqx.io:1883"
                val clientId = "speaker_$DEVICE_ID"
                mqttClient = MqttClient(broker, clientId, null)
                val options = MqttConnectOptions()
                options.isAutomaticReconnect = true
                options.isCleanSession = true

                // 标准MQTT回调，接收所有消息
                mqttClient.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        val downTopic = "smartscreen/speaker/$DEVICE_ID/down"
                        mqttClient.subscribe(downTopic, 1)
                        LogManager.addLog("Speaker服务", "已连接MQTT，订阅主题: $downTopic")
                    }

                    override fun connectionLost(cause: Throwable?) {}

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (message != null) {
                            handleSpeakerMessage(String(message.payload))
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient.connect(options)
            } catch (e: Exception) {
                LogManager.addLog("Speaker服务异常", e.message ?: "MQTT连接失败")
            }
        }.start()
    }

    private fun handleSpeakerMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val messageId = json.getString("messageId")
            val deviceId = json.getString("deviceId")
            val amount = json.getDouble("amount")
            val paymentType = json.getString("paymentType")
            val message = json.getString("message")
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            LogManager.addLog("MESSAGE_RECEIVED", "messageId=$messageId, 内容:$message")

            // 去重判断
            if (playedMessageIds.contains(messageId)) {
                LogManager.addLog("MESSAGE_DUPLICATE", "重复消息$messageId，不播报")
                sendAck(messageId, "DUPLICATE")
                return
            }

            // 保存交易记录
            try {
                val db = openOrCreateDatabase("speaker_transactions.db", MODE_PRIVATE, null)
                db.execSQL("CREATE TABLE IF NOT EXISTS transactions (messageId TEXT PRIMARY KEY, deviceId TEXT, amount REAL, paymentType TEXT, message TEXT, receivedAt TEXT, playedAt TEXT, status TEXT)")
                db.execSQL("INSERT OR REPLACE INTO transactions VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf(messageId, deviceId, amount, paymentType, message, now, "", "RECEIVED"))
                db.close()
            } catch (e: Exception) {}

            // TTS播报
            playedMessageIds.add(messageId)
            if (ttsReady) {
                LogManager.addLog("TTS_STARTED", "开始播报: $message")
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, messageId)
                LogManager.addLog("TTS_FINISHED", "播报完成: $message")
                try {
                    val db = openOrCreateDatabase("speaker_transactions.db", MODE_PRIVATE, null)
                    db.execSQL("UPDATE transactions SET playedAt=?, status=? WHERE messageId=?",
                        arrayOf(now, "PLAYED", messageId))
                    db.close()
                } catch (e: Exception) {}
            }

            sendAck(messageId, "PLAYED")
        } catch (e: Exception) {
            LogManager.addLog("Speaker消息处理异常", e.message ?: "未知错误")
        }
    }

    private fun sendAck(messageId: String, status: String) {
        try {
            val upTopic = "smartscreen/speaker/$DEVICE_ID/up"
            val ackPayload = JSONObject().apply {
                put("messageId", messageId)
                put("status", status)
                put("timestamp", System.currentTimeMillis())
            }.toString()
            mqttClient.publish(upTopic, MqttMessage(ackPayload.toByteArray()))
            LogManager.addLog("ACK_SENT", "messageId=$messageId, status=$status")
        } catch (e: Exception) {
            LogManager.addLog("ACK发送失败", e.message ?: "未知错误")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.CHINESE
            ttsReady = true
            LogManager.addLog("TTS初始化", "TTS准备完成")
        } else {
            LogManager.addLog("TTS初始化失败", "错误码: $status")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        try { mqttClient.disconnect() } catch (e: Exception) {}
    }
}
