package com.smartpay.alipaylistener

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.*

/**
 * MQTT客户端管理器（全网通）
 */
object MqttClientManager {

    private const val MQTT_BROKER = "tcp://broker.emqx.io:1883"
    private const val TOPIC_PREFIX = "smartpay/v1/"

    private var mqttClient: MqttClient? = null
    private var isConnected = false
    private var isPaired = false
    private var deviceId = ""
    private var pairCode = ""
    private var logCallback: ((String) -> Unit)? = null
    private var statusCallback: ((String, String) -> Unit)? = null

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }

    fun setStatusCallback(callback: (String, String) -> Unit) {
        statusCallback = callback
    }

    fun isConnected(): Boolean = isConnected
    fun isPaired(): Boolean = isPaired

    fun connect(pairCode: String) {
        this.pairCode = pairCode
        this.deviceId = "android_" + UUID.randomUUID().toString().substring(0, 8)

        log("设备ID: $deviceId")
        log("配对码: $pairCode")
        log("正在连接MQTT服务器: $MQTT_BROKER")

        try {
            mqttClient = MqttClient(MQTT_BROKER, deviceId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
                isAutomaticReconnect = true
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    isPaired = false
                    log("连接断开: ${cause?.message}")
                    statusCallback?.invoke("disconnected", "连接断开")
                    // 5秒后重连
                    Thread.sleep(5000)
                    connect(pairCode)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    try {
                        val payload = String(message?.payload ?: ByteArray(0))
                        log("收到消息: $topic -> $payload")

                        if (topic?.contains("pair_response") == true) {
                            val json = JSONObject(payload)
                            if (json.optString("status") == "success") {
                                isPaired = true
                                log("✅ 配对成功！")
                                statusCallback?.invoke("paired", "配对成功")
                            }
                        }
                    } catch (e: Exception) {
                        log("处理消息异常: ${e.message}")
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options)
            isConnected = true
            log("✅ MQTT连接成功")
            statusCallback?.invoke("connected", "连接成功")

            // 订阅配对响应主题
            val responseTopic = "${TOPIC_PREFIX}pair_response/$deviceId"
            mqttClient?.subscribe(responseTopic)
            log("已订阅: $responseTopic")

            // 发送配对请求
            sendPairRequest()

        } catch (e: Exception) {
            log("❌ MQTT连接异常: ${e.message}")
            statusCallback?.invoke("error", "连接失败")
            Thread.sleep(5000)
            connect(pairCode)
        }
    }

    private fun sendPairRequest() {
        try {
            val pairTopic = "${TOPIC_PREFIX}pair/$pairCode"
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("device_name", android.os.Build.BRAND + " " + android.os.Build.MODEL)
            }
            val message = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
            mqttClient?.publish(pairTopic, message)
            log("已发送配对请求")
            statusCallback?.invoke("pairing", "等待配对")
        } catch (e: Exception) {
            log("发送配对请求失败: ${e.message}")
        }
    }

    fun sendPayment(amount: String, rawText: String) {
        if (!isConnected || !isPaired || mqttClient == null) {
            log("未连接或未配对，无法发送")
            return
        }

        try {
            val eventId = "android_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4)
            val paymentTopic = "${TOPIC_PREFIX}payment/$deviceId"
            val json = JSONObject().apply {
                put("event_id", eventId)
                put("device_id", deviceId)
                put("source", "ALIPAY")
                put("amount", amount)
                put("received_at", System.currentTimeMillis())
                put("raw_text", rawText)
            }
            val message = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
            mqttClient?.publish(paymentTopic, message)
            log("✅ 已推送收款: ¥$amount")
        } catch (e: Exception) {
            log("发送收款失败: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient = null
            isConnected = false
            isPaired = false
            log("已断开连接")
        } catch (e: Exception) {
            log("断开异常: ${e.message}")
        }
    }

    private fun log(msg: String) {
        LogManager.addLog("MQTT", msg)
    }
}
