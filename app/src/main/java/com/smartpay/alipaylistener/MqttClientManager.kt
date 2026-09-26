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
    private var deviceId = ""  // 设备ID固定不变，重连也用同一个
    private var pairCode = ""
    private var isReconnecting = false  // 防止重复重连
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
        
        // 设备ID只生成一次，之后重连一直用同一个
        if (deviceId.isEmpty()) {
            deviceId = "android_" + UUID.randomUUID().toString().substring(0, 8)
        }

        log("设备ID: $deviceId")
        log("配对码: $pairCode")
        log("正在连接MQTT服务器: $MQTT_BROKER")

        try {
            // 先清理旧连接
            try { mqttClient?.disconnect() } catch (_: Exception) {}
            mqttClient = null

            mqttClient = MqttClient(MQTT_BROKER, deviceId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 60
                isAutomaticReconnect = false  // 关闭自动重连，只用手动重连，避免冲突
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    isPaired = false
                    log("连接断开: ${cause?.message}")
                    statusCallback?.invoke("disconnected", "连接断开")
                    // 5秒后重连（用同一个deviceId）
                    scheduleReconnect()
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
            isReconnecting = false
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
            // 5秒后重连
            scheduleReconnect()
        }
    }

    /**
     * 安排重连（防止重复重连）
     */
    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        
        Thread {
            Thread.sleep(5000)
            isReconnecting = false
            log("正在重连...")
            connect(pairCode)
        }.start()
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

    fun sendPayment(amount: String, rawText: String, appName: String = "", source: String = "ALIPAY") {
        log("收到收款通知准备发送: 金额=$amount, 原始内容=${rawText.take(50)}, 公众号=$appName")
        log("MQTT状态: isConnected=$isConnected, isPaired=$isPaired, client是否为空=${mqttClient == null}")
        if (!isConnected || !isPaired || mqttClient == null) {
            log("❌ 未连接或未配对，丢弃这笔收款通知: ¥$amount")
            return
        }

        try {
            val eventId = "android_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4)
            val paymentTopic = "${TOPIC_PREFIX}payment/$deviceId"
            val json = JSONObject().apply {
                put("event_id", eventId)
                put("device_id", deviceId)
                put("source", source)
                put("amount", amount)
                put("received_at", System.currentTimeMillis())
                put("raw_text", rawText)
                put("app_name", appName)
            }
            log("准备发布MQTT: topic=$paymentTopic, 金额=$amount, eventId=$eventId")
            val message = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
            mqttClient?.publish(paymentTopic, message)
            log("✅ MQTT发布成功: ¥$amount, topic=$paymentTopic")
        } catch (e: Exception) {
            log("❌ MQTT发布失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun sendWeipayTrigger() {
        log("发送邮付拉单触发消息到PC")
        if (!isConnected || !isPaired || mqttClient == null) {
            log("❌ MQTT未连接，丢弃触发消息")
            return
        }
        try {
            val triggerTopic = "smartscreen/payment/trigger"
            val json = JSONObject().apply {
                put("source", "wepay_postpay")
                put("time", System.currentTimeMillis())
                put("device_id", deviceId)
            }
            val message = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
            mqttClient?.publish(triggerTopic, message)
            log("✅ 邮付拉单触发已发送")
        } catch (e: Exception) {
            log("❌ 发送触发失败: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient = null
            isConnected = false
            isPaired = false
            isReconnecting = false
            log("已断开连接")
        } catch (e: Exception) {
            log("断开异常: ${e.message}")
        }
    }

    private fun log(msg: String) {
        LogManager.addLog("MQTT", msg)
    }
}

