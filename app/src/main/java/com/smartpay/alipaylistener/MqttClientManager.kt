package com.smartpay.alipaylistener

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.*
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * MQTT客户端管理器（全网通）
 */
object MqttClientManager {

    private const val MQTT_BROKER = "ssl://broker.emqx.io:8883"
    private const val TOPIC_PREFIX = "smartpay/v1/"
    private const val ORDER_RESULT_TOPIC = TOPIC_PREFIX + "order_result/"

    private val connectionLock = Any()
    private var mqttClient: MqttClient? = null
    @Volatile private var isConnected = false
    @Volatile private var isConnecting = false
    @Volatile private var isPaired = false
    private var deviceId = ""  // 设备ID固定不变，重连也用同一个
    private var pairCode = ""
    @Volatile private var isReconnecting = false  // 表示已有一个延迟重连任务
    private val pendingTriggerQueue = mutableListOf<String>()  // 断线时暂存银行卡触发消息，连接恢复后补发
    private var logCallback: ((String) -> Unit)? = null
    private var statusCallback: ((String, String) -> Unit)? = null
    private var paymentCallback: ((String, String) -> Unit)? = null
    // 使用独立后台调度器计时，避免主线程繁忙导致重连任务延迟。
    private val reconnectScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MQTT-Reconnect-Timer").apply { isDaemon = true }
    }
    private var reconnectFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val reconnectRunnable = Runnable {
        val code = synchronized(connectionLock) {
            isReconnecting = false
            if (isConnected || isConnecting || pairCode.isBlank()) null else pairCode
        }
        if (code != null) {
            log("正在重连...")
            // MqttClient.connect() 是同步调用，不能放在主线程 Handler 上执行。
            Thread({ connect(code) }, "MQTT-Reconnect").start()
        }
    }
    // 心跳记录变量，只记录最后一次ping时间，不打实时日志
    @Volatile private var lastPingReqTime = 0L
    @Volatile private var lastPingRespTime = 0L
    @Volatile private var pingOutstanding = false
    private var pahoDiagnosticsConfigured = false

    // Paho trace records use numeric message IDs; inspect parameters as well as message text.
    private val pahoDiagnosticHandler = object : java.util.logging.Handler() {
        override fun publish(record: java.util.logging.LogRecord) {
            try {
                val params = record.parameters?.joinToString(" ") { it?.toString() ?: "" } ?: ""
                val detail = "${record.message ?: ""} $params"
                val now = System.currentTimeMillis()
                val time = android.text.format.DateFormat.format("HH:mm:ss.SSS", now).toString()
                if (detail.contains("PINGREQ", ignoreCase = true)) {
                    lastPingReqTime = now
                    pingOutstanding = true
                    log("[MQTT_HEARTBEAT] PINGREQ time=$time")
                }
                if (detail.contains("PINGRESP", ignoreCase = true)) {
                    lastPingRespTime = now
                    pingOutstanding = false
                    log("[MQTT_HEARTBEAT] PINGRESP time=$time")
                }
                if (record.level.intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    val thrown = record.thrown?.stackTraceToString()
                    log("[MQTT_COMMS_ERROR] ${record.level.name}: $detail${if (thrown.isNullOrBlank()) "" else "\n$thrown"}")
                }
            } catch (_: Exception) {
            }
        }
        override fun flush() {}
        override fun close() {}
    }

    private fun configurePahoDiagnostics() {
        synchronized(connectionLock) {
            if (pahoDiagnosticsConfigured) return
            try {
                val logger = java.util.logging.Logger.getLogger("org.eclipse.paho.client.mqttv3.internal")
                // Enable Paho trace records internally, but emit only ping events and errors.
                logger.level = java.util.logging.Level.ALL
                logger.addHandler(pahoDiagnosticHandler)
                logger.useParentHandlers = false
                pahoDiagnosticsConfigured = true
                log("Paho心跳诊断已启用：仅输出PINGREQ/PINGRESP和错误")
            } catch (e: Exception) {
                log("配置Paho心跳诊断失败: ${e.message}")
            }
        }
    }

    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }

    fun setStatusCallback(callback: (String, String) -> Unit) {
        statusCallback = callback
    }

    fun isConnected(): Boolean = isConnected
    fun isPaired(): Boolean = isPaired

    fun setPaymentCallback(callback: (String, String) -> Unit) { paymentCallback = callback }

    fun connect(pairCode: String) {
        val skipReason = synchronized(connectionLock) {
            when {
                isConnected && mqttClient?.isConnected == true -> "MQTT已经连接"
                isConnecting -> "MQTT正在连接"
                isReconnecting -> "已有一个延迟重连任务"
                else -> {
                    if (pairCode.isNotBlank()) this.pairCode = pairCode
                    isConnecting = true
                    null
                }
            }
        }
        log("🔍 定位: connect() 被调用, 时间=${System.currentTimeMillis()}, pairCode=$pairCode, isConnected=$isConnected, isConnecting=$isConnecting, isPaired=$isPaired")
        if (skipReason != null) {
            log("⚠️ $skipReason，跳过重复connect调用")
            return
        }
        try {
            throw Exception("调用来源栈")
        } catch (e: Exception) {
            log("🔍 定位: connect() 调用栈: ${e.stackTraceToString().take(500)}")
        }
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

            configurePahoDiagnostics()

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
                isAutomaticReconnect = false  // 关闭自动重连，只用手动重连，避免冲突
                // TLS连接：使用系统默认信任的SSL SocketFactory，无需自定义证书
                socketFactory = javax.net.ssl.SSLContext.getDefault().socketFactory
            }
            log("[MQTT_CONFIG] keepAlive=${options.keepAliveInterval}s cleanSession=${options.isCleanSession} automaticReconnect=${options.isAutomaticReconnect} connectionTimeout=${options.connectionTimeout}s broker=$MQTT_BROKER")
            lastPingReqTime = 0L
            lastPingRespTime = 0L
            pingOutstanding = false

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    synchronized(connectionLock) {
                        isConnected = true
                        isConnecting = false
                        isReconnecting = false
                        reconnectFuture?.cancel(false)
                        reconnectFuture = null
                    }
                    log("✅ MQTT连接成功，已连接到服务器: $serverURI, 是否重连=$reconnect")
                    statusCallback?.invoke("connected", "连接成功")
                    // 订阅配对响应主题
                    val responseTopic = "${TOPIC_PREFIX}pair_response/$deviceId"
                    mqttClient?.subscribe(responseTopic)
                    // 如果已经配对过，直接订阅收款主题，不发配对请求
                    if (isPaired) {
                        val paymentTopic = "${TOPIC_PREFIX}payment/$deviceId"
                        mqttClient?.subscribe(paymentTopic)
                        val orderResultTopic = ORDER_RESULT_TOPIC + deviceId
                        mqttClient?.subscribe(orderResultTopic)
                        log("已订阅收款主题: $paymentTopic")
                        log("已订阅PC订单回传主题: $orderResultTopic")
                        log("🔍 断线重连成功，直接恢复订阅，跳过配对")
                    } else {
                        // 首次连接才发配对请求
                        log("🔍 首次连接，发送配对请求")
                        sendPairRequest()
                    }
                    // 连接恢复后补发暂存的触发消息
                    flushPendingTriggers()
                }
                override fun connectionLost(cause: Throwable?) {
                    synchronized(connectionLock) {
                        isConnected = false
                        isConnecting = false
                    }
                    // 断线保留已配对状态，重连不需要重新配对
                    log("=== [MQTT_SOCKET_DIAG] 断线现场诊断 ===")
                    log("exception_type: ${cause?.javaClass?.name ?: "主动断开"}")
                    log("exception_message: ${cause?.message ?: "无"}")
                    cause?.let {
                        var current: Throwable? = it
                        var depth = 0
                        while (current != null && depth < 8) {
                            log("exception_chain[$depth]: ${current.javaClass.name}: ${current.message}")
                            current = current.cause
                            depth++
                        }
                        log("exception_full_stack: ${it.stackTraceToString()}")
                    }
                    // Paho 1.2.5 内部Socket为私有字段，外部无法直接获取
                    log("socket_diag: Paho 1.2.5内部Socket为私有封装，外部无法直接读取socket状态")
                    // 打印最后一次心跳摘要
                    val fmtTime = { ts: Long -> if (ts == 0L) "无记录" else android.text.format.DateFormat.format("HH:mm:ss.SSS", ts).toString() }
                    val currentTime = android.text.format.DateFormat.format("HH:mm:ss.SSS", System.currentTimeMillis()).toString()
                    log("heartbeat_summary: now=$currentTime lastPingReq=${fmtTime(lastPingReqTime)} lastPingResp=${fmtTime(lastPingRespTime)} pingOutstanding=$pingOutstanding")
                    // 打印当前网络状态
                    try {
                        val ctx = KeepAliveService.instance
                        if (ctx != null) {
                            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                            val activeNet = cm.activeNetwork
                            val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
                            val netType = when {
                                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "蜂窝移动数据"
                                else -> "其他/无网络"
                            }
                            log("network_status: 当前网络=$netType activeNetwork=$activeNet")
                        } else {
                            log("network_status: 服务未运行，无法获取网络状态")
                        }
                    } catch (e: Exception) {
                        log("network_status: 获取网络状态失败: ${e.message}")
                    }
                    log("保留已配对状态 isPaired=$isPaired")
                    log("=== 断线现场诊断结束 ===")
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
                                val paymentTopic = "${TOPIC_PREFIX}payment/$deviceId"
                                val orderResultTopic = ORDER_RESULT_TOPIC + deviceId
                                mqttClient?.subscribe(paymentTopic)
                                mqttClient?.subscribe(orderResultTopic)
                                log("✅ 配对成功！已订阅收款与PC订单回传")
                                statusCallback?.invoke("paired", "配对成功")
                            }
                        } else if (topic == ORDER_RESULT_TOPIC + deviceId) {
                            val json = JSONObject(payload)
                            val amount = json.optString("amount", "").trim()
                            val channel = json.optString("channel_name", json.optString("app_name", json.optString("channel", ""))).trim()
                            val orderId = json.optString("order_id", json.optString("order_no", "")).trim()
                            if (amount.isNotEmpty()) {
                                val displayChannel = if (channel.isNotEmpty()) channel else "收款"
                                log("收到PC订单回传: 金额=$amount, 渠道=$displayChannel, 订单号=$orderId")
                                paymentCallback?.invoke(amount, displayChannel)
                            } else {
                                log("PC订单回传缺少金额，忽略: $payload")
                            }
                        }
                    } catch (e: Exception) {
                        log("处理消息异常: ${e.message}")
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            log("正在连接MQTT服务器，等待连接建立...")
            mqttClient?.connect(options)
            // 连接真正建立后，在MqttCallback的connectComplete回调里再订阅和发配对请求

        } catch (e: Exception) {
            synchronized(connectionLock) {
                isConnecting = false
                isConnected = false
            }
            log("❌ MQTT连接异常: ${e.message}")
            log("exception_full_stack: ${e.stackTraceToString()}")
            statusCallback?.invoke("error", "连接失败")
            scheduleReconnect()
        }
    }

    /** 网络恢复时提前尝试现有手动重连流程，不启用第二套自动重连。 */
    fun onNetworkAvailable() {
        val code = synchronized(connectionLock) {
            if (pairCode.isBlank() || isConnecting || (isConnected && mqttClient?.isConnected == true)) {
                null
            } else {
                if (isReconnecting) {
                    reconnectFuture?.cancel(false)
                    reconnectFuture = null
                    isReconnecting = false
                }
                pairCode
            }
        } ?: return

        log("网络恢复且MQTT未连接，提前尝试恢复连接")
        Thread({ connect(code) }, "MQTT-Network-Recovery").start()
    }

    /** Handler 只负责计时；同步网络连接始终放在后台线程。 */
    private fun scheduleReconnect() {
        synchronized(connectionLock) {
            if (isConnected || isConnecting || isReconnecting) return
            isReconnecting = true
            reconnectFuture?.cancel(false)
            reconnectFuture = reconnectScheduler.schedule(reconnectRunnable, 5, java.util.concurrent.TimeUnit.SECONDS)
        }
        log("已安排5秒后重连")
    }

    private fun sendPairRequest() {
        log("🔍 定位: sendPairRequest() 被调用, 时间=${System.currentTimeMillis()}")
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
        log("收到收款通知: 金额=$amount, 原始内容=${rawText.take(50)}, 公众号=$appName")
        // 第一步：不管MQTT是否连接，先执行本地UI更新和TTS播报，本地收款完全不依赖MQTT
        val displayChannel = if (source == "WECHAT") "微信" else if (source == "ALIPAY") "支付宝" else if (appName.isNotEmpty()) appName else "收款"
        paymentCallback?.invoke(amount, displayChannel)
        // 第二步：本地处理完成后，再判断是否要发送MQTT到PC，未连接则只跳过网络发送，不丢弃本地收款
        log("MQTT状态: isConnected=$isConnected, isPaired=$isPaired, client是否为空=${mqttClient == null}")
        if (!isConnected || !isPaired || mqttClient == null) {
            log("⚠️ MQTT未连接，跳过发送到PC，本地播报已完成: ¥$amount")
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

    fun sendWeipayTrigger(appName: String = "邮付小助手") {
        log("发送银行卡拉单触发消息到PC: $appName")
        if (!isConnected || !isPaired || mqttClient == null) {
            log("⚠️ MQTT未连接，触发消息暂存本地队列，等待重连后补发")
            pendingTriggerQueue.add(appName)
            return
        }
        try {
            val triggerTopic = "smartscreen/payment/trigger"
            val json = JSONObject().apply {
                put("source", "bank_trigger")
                put("app_name", appName)
                put("time", System.currentTimeMillis())
                put("device_id", deviceId)
            }
            val message = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
            mqttClient?.publish(triggerTopic, message)
            log("✅ 邮付拉单触发已发送")
        } catch (e: Exception) {
            log("❌ 发送触发失败: ${e.message}，消息转入待发送队列")
            pendingTriggerQueue.add(appName)
        }
    }

    private fun flushPendingTriggers() {
        if (pendingTriggerQueue.isEmpty()) return
        log("🔄 MQTT连接恢复，补发暂存的${pendingTriggerQueue.size}条银行卡触发消息")
        val triggerTopic = "smartscreen/payment/trigger"
        val iterator = pendingTriggerQueue.iterator()
        while (iterator.hasNext()) {
            val appName = iterator.next()
            try {
                val json = JSONObject().apply {
                    put("source", "bank_trigger")
                    put("app_name", appName)
                    put("time", System.currentTimeMillis())
                    put("device_id", deviceId)
                }
                val message = MqttMessage(json.toString().toByteArray()).apply { qos = 1 }
                mqttClient?.publish(triggerTopic, message)
                log("✅ 补发成功: $appName")
                iterator.remove()
            } catch (e: Exception) {
                log("❌ 补发失败: $appName")
                break
            }
        }
    }

    fun disconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        try {
            mqttClient?.disconnect()
            synchronized(connectionLock) {
                mqttClient = null
                isConnected = false
                isConnecting = false
                isPaired = false
                isReconnecting = false
            }
            log("已断开连接")
        } catch (e: Exception) {
            log("断开异常: ${e.message}")
        }
    }

    private fun log(msg: String) {
        LogManager.addLog("MQTT", msg)
    }
}








