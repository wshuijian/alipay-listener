package com.smartpay.alipaylistener

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 云音箱秒播MVP服务 - 最小改动实现，不影响现有通知监听逻辑
 */
class SpeakerService : Service(), TextToSpeech.OnInitListener {
    private lateinit var mqttClient: MqttClient
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val playedMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val db by lazy {
        openOrCreateDatabase("speaker_transactions.db", MODE_PRIVATE, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS transactions (messageId TEXT PRIMARY KEY, deviceId TEXT, amount REAL, paymentType TEXT, message TEXT, receivedAt TEXT, playedAt TEXT, status TEXT)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        initMqtt()
        loadHistoryPlayedIds()
    }

    private fun loadHistoryPlayedIds() {
        val cursor = db.rawQuery("SELECT messageId FROM transactions WHERE status='PLAYED'", null)
        while (cursor.moveToNext()) {
            playedMessageIds.add(cursor.getString(0))
        }
        cursor.close()
    }

    private fun initMqtt() {
        try {
            val deviceId = "TEST-001"
            val broker = "tcp://broker.emqx.io:1883"
            val clientId = "speaker_$deviceId"
            mqttClient = MqttClient(broker, clientId, null)
            val options = MqttConnectOptions()
            options.isAutomaticReconnect = true
            options.isCleanSession = true
            mqttClient.connect(options)

            // 订阅下行主题：smartscreen/speaker/TEST-001/down
            val downTopic = "smartscreen/speaker/$deviceId/down"
            mqttClient.subscribe(downTopic) { topic, message ->
                handleSpeakerMessage(String(message.payload))
            }
            LogManager.addLog("Speaker服务", "已连接MQTT，订阅主题: $downTopic")
        } catch (e: Exception) {
            LogManager.addLog("Speaker服务异常", e.message ?: "MQTT连接失败")
        }
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
            db.execSQL("INSERT OR REPLACE INTO transactions VALUES (?,?,?,?,?,?,?,?)",
                arrayOf(messageId, deviceId, amount, paymentType, message, now, "", "RECEIVED"))

            // TTS播报
            playedMessageIds.add(messageId)
            if (ttsReady) {
                LogManager.addLog("TTS_STARTED", "开始播报: $message")
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, messageId)
                LogManager.addLog("TTS_FINISHED", "播报完成: $message")
                db.execSQL("UPDATE transactions SET playedAt=?, status=? WHERE messageId=?",
                    arrayOf(now, "PLAYED", messageId))
            }

            sendAck(messageId, "PLAYED")
        } catch (e: Exception) {
            LogManager.addLog("Speaker消息处理异常", e.message ?: "未知错误")
        }
    }

    private fun sendAck(messageId: String, status: String) {
        try {
            val upTopic = "smartscreen/speaker/TEST-001/up"
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
        mqttClient.disconnect()
    }
}
