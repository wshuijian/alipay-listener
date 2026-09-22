package com.smartpay.alipaylistener

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class AlipayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AlipayListener"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val ALIPAY_PAY_CHANNEL = "alipay_default"

        // 第三阶段：纯诊断模式，只记录，不解析，不发送MQTT
        private const val DIAGNOSTICS_ONLY = false

        // 防重复：记录已经处理过的通知key
        private val processedKeys = mutableSetOf<String>()

        // 记录已经出现过的通知key，用于判断是POST还是UPDATE
        private val seenNotificationKeys = mutableSetOf<String>()

        // 金额正则：从"你已成功收款0.01元"里提取0.01
        private val ALIPAY_AMOUNT_PATTERN = Pattern.compile("你已成功收款([\\d]+\\.?[\\d]*)元")
        // 微信金额正则：从"微信支付收款0.01元"里提取0.01
        private val WECHAT_AMOUNT_PATTERN = Pattern.compile("微信支付收款([\\d]+\\.?[\\d]*)元")
        // 通用聚合收款正则：所有第三方聚合码/银行公众号通知，只要有收款/到账+金额就提取
        private val AGGREGATE_AMOUNT_PATTERN = Pattern.compile("(?:收款|到账).*?([\\d]+\\.?[\\d]*)元")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogManager.addLog("系统", "通知监听服务已连接")
        Log.i(TAG, "通知监听服务已连接")
        dumpActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogManager.addLog("系统", "通知监听服务已断开")
        Log.i(TAG, "通知监听服务已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) {
            LogManager.addLog("通知POST", "sbn == null")
            Log.w(TAG, "onNotificationPosted: sbn == null")
            return
        }

        // 最开头先判断是不是新POST，必须在logNotificationPosted加集合之前判断
        val isNewPost = !seenNotificationKeys.contains(sbn.key)
        // 时间过滤：只处理10秒内新到的通知，刚启动时历史通知直接跳过
        val now = System.currentTimeMillis()
        val timeDiff = now - sbn.postTime

        logNotificationPosted(sbn)

        // 调试阶段到此为止，不进入金额解析/MQTT 流程，避免多个问题混在一起。
        if (!DIAGNOSTICS_ONLY) {
            if (timeDiff > 10000) {
                LogManager.addLog("通知过滤", "跳过${timeDiff/1000}秒前的历史通知: $sbn.packageName")
                return
            }
            // 注意：微信收款通知是常驻同一条，每次新收款都是UPDATE事件，不能跳过UPDATE
            // 只靠10秒时间过滤，重复播报由PC端去重逻辑处理
            processNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) {
            LogManager.addLog("通知REMOVE", "sbn == null")
            Log.w(TAG, "onNotificationRemoved: sbn == null")
            return
        }
        logNotificationRemoved(sbn, null)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        if (sbn == null) {
            LogManager.addLog("通知REMOVE", "sbn == null, reason=$reason")
            Log.w(TAG, "onNotificationRemoved: sbn == null, reason=$reason")
            return
        }
        logNotificationRemoved(sbn, reason)
    }

    private fun logNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification?.extras

        // 判断是新通知POST还是通知更新UPDATE
        val event: String
        if (seenNotificationKeys.contains(sbn.key)) {
            event = "UPDATE"
        } else {
            event = "POST"
            seenNotificationKeys.add(sbn.key)
        }

        val summary = buildNotificationSummary(sbn, event)

        LogManager.addLog("通知$event", summary)
        Log.i(TAG, "onNotificationPosted ($event)\n$summary")

        // 跑通后关闭冗长的extras字段打印，只保留关键日志
        if (sbn.packageName == ALIPAY_PACKAGE) {
            LogManager.addLog("支付宝${event}", "收到支付宝${event}通知")
        }
    }

    private fun logNotificationRemoved(sbn: StatusBarNotification, reason: Int?) {
        val notification = sbn.notification
        val extras = notification?.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        val summary = buildString {
            appendLine("packageName=${sbn.packageName}")
            appendLine("key=${sbn.key}")
            appendLine("id=${sbn.id}")
            appendLine("tag=${sbn.tag}")
            appendLine("reason=${reason?.toString() ?: "N/A(单参数回调未提供 reason)"}")
            appendLine("title=$title")
            appendLine("text=$text")
            appendLine("bigText=$bigText")
            appendLine("postTime=${sbn.postTime} (${formatTime(sbn.postTime)})")
        }.trimEnd()

        LogManager.addLog("通知REMOVE", summary)
        Log.i(TAG, "onNotificationRemoved\n$summary")
    }

    private fun dumpActiveNotifications() {
        try {
            val active = getActiveNotifications()
            val count = active?.size ?: 0
            LogManager.addLog("系统", "onListenerConnected: activeNotifications 数量=$count")
            Log.i(TAG, "onListenerConnected: activeNotifications count=$count")

            if (active == null || active.isEmpty()) {
                LogManager.addLog("系统", "当前没有 active notifications")
                return
            }

            active.forEach { sbn ->
                val summary = buildNotificationSummary(sbn, "ACTIVE")

                LogManager.addLog("通知ACTIVE", summary)
                Log.i(TAG, "active notification\n$summary")

                if (sbn.packageName == ALIPAY_PACKAGE) {
                    LogManager.addLog("支付宝ACTIVE", "active 中存在支付宝通知，输出完整 extras")
                    Log.i(TAG, "Alipay active notification, dumping extras")
                    dumpExtras(sbn.notification?.extras, "支付宝ACTIVE extras")
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("系统", "读取 activeNotifications 异常: ${e.message}")
            Log.e(TAG, "getActiveNotifications failed", e)
        }
    }

    private fun buildNotificationSummary(sbn: StatusBarNotification, source: String): String {
        val notification = sbn.notification
        val extras = notification?.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        return buildString {
            appendLine("source=$source")
            appendLine("packageName=${sbn.packageName}")
            appendLine("key=${sbn.key}")
            appendLine("id=${sbn.id}")
            appendLine("tag=${sbn.tag}")
            appendLine("postTime=${sbn.postTime} (${formatTime(sbn.postTime)})")
            appendLine("groupKey=${groupKeyOf(sbn)}")
            appendLine("isGroup=${isGroupOf(sbn)}")
            appendLine("flags=${flagsOf(notification)}")
            appendLine("channelId=${channelIdOf(notification)}")
            appendLine("title=$title")
            appendLine("text=$text")
            appendLine("bigText=$bigText")
            appendLine("subText=$subText")
            appendLine("tickerText=${notification?.tickerText}")
        }.trimEnd()
    }

    private fun dumpExtras(extras: Bundle?, label: String) {
        if (extras == null) {
            LogManager.addLog(label, "extras == null")
            Log.i(TAG, "$label: extras == null")
            return
        }

        if (extras.keySet().isEmpty()) {
            LogManager.addLog(label, "extras 为空")
            Log.i(TAG, "$label: extras is empty")
            return
        }

        val keys = extras.keySet().sorted()
        LogManager.addLog(label, "extras 共 ${keys.size} 个字段")
        keys.forEach { key ->
            val line = "$key=${describeExtrasValue(extras, key)}"
            LogManager.addLog(label, line)
            Log.i(TAG, "$label: $line")
        }
    }

    private fun describeExtrasValue(bundle: Bundle, key: String): String {
        return try {
            when (val value = bundle.get(key)) {
                null -> "null"
                is Bundle -> "Bundle{${value.keySet().joinToString(", ")}}"
                is CharSequence -> value.toString()
                is Array<*> -> value.contentToString()
                is IntArray -> value.contentToString()
                is LongArray -> value.contentToString()
                is ShortArray -> value.contentToString()
                is ByteArray -> "ByteArray(size=${value.size})"
                is FloatArray -> value.contentToString()
                is DoubleArray -> value.contentToString()
                is BooleanArray -> value.contentToString()
                is CharArray -> value.contentToString()
                else -> "${value.javaClass.simpleName}: $value"
            }
        } catch (e: Exception) {
            "<无法读取: ${e.message}>"
        }
    }

    private fun groupKeyOf(sbn: StatusBarNotification): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            sbn.groupKey ?: "null"
        } else {
            "N/A(<20)"
        }
    }

    private fun isGroupOf(sbn: StatusBarNotification): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            sbn.isGroup.toString()
        } else {
            "N/A(<20)"
        }
    }

    private fun flagsOf(notification: Notification?): String {
        val flags = notification?.flags ?: return "null"
        return "$flags (0x${Integer.toHexString(flags)})"
    }

    private fun channelIdOf(notification: Notification?): String {
        if (notification == null) return "null"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.channelId ?: "null"
        } else {
            "N/A(<26)"
        }
    }

    private fun formatTime(millis: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(millis))
        } catch (e: Exception) {
            millis.toString()
        }
    }

    /**
     * 处理收款通知
     * 同时支持支付宝和微信
     */
    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE, "")?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT, "")?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "")?.toString() ?: ""

            // 防重复：用通知key+postTime去重
            val eventKey = "${sbn.key}_${sbn.postTime}"
            if (processedKeys.contains(eventKey)) {
                LogManager.addLog("防重复", "同一条通知，忽略")
                return
            }
            processedKeys.add(eventKey)
            if (processedKeys.size > 100) processedKeys.clear()

            // 先识别银行卡收款：不管哪个包名，只要标题/内容包含银行卡到账/收款关键词，就直接处理发送
            val fullText = "$title $text $bigText"
            val bankMatcher = BANK_CARD_AMOUNT_PATTERN.matcher(fullText)
            if (bankMatcher.find()) {
                val bankAmount = bankMatcher.group(1)
                LogManager.addLog("✅ 银行卡收款解析成功", "金额:¥$bankAmount")
                LogManager.addLog("MQTT", "正在发送银行卡金额${bankAmount}到PC端...")
                MqttClientManager.sendPayment(amount = bankAmount, rawText = "BANKCARD|$title $text")
                LogManager.addLog("MQTT", "发送完成")
                return  // 处理完直接返回，不进后面的微信/支付宝分支
            }

            // 全局诊断：所有新通知都打印包名和内容，方便抓聚合码等其他收款APP的通知
            if (packageName == WECHAT_PACKAGE && title != "微信收款助手") {
                LogManager.addLog("其他微信通知", "标题=$title | 内容=$text | 展开内容=$bigText")
            }
            if (packageName != ALIPAY_PACKAGE && packageName != WECHAT_PACKAGE && packageName != "com.smartpay.alipaylistener") {
                LogManager.addLog("其他通知", "包名=$packageName | 标题=$title | 内容=$text | 展开内容=$bigText")
            }

            when (packageName) {
                // 处理支付宝
                ALIPAY_PACKAGE -> {
                    val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        notification.channelId ?: ""
                    } else {
                        ""
                    }
                    // 只处理收款通知通道，排除"收钱提醒助手"那个voice_helper通道
                    if (channelId != ALIPAY_PAY_CHANNEL) {
                        return
                    }

                    LogManager.addLog("支付宝收款通知", "title=$title | text=$text")

                    // 从title中提取金额："你已成功收款0.01元（老顾客消费）" → 0.01
                    val matcher = ALIPAY_AMOUNT_PATTERN.matcher(title)
                    if (!matcher.find()) {
                        LogManager.addLog("支付宝解析失败", "title中没找到金额")
                        return
                    }

                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 支付宝解析成功", "金额:¥$amount")

                    // 发送给PC端
                    LogManager.addLog("MQTT", "正在发送支付宝金额${amount}到PC端...")
                    MqttClientManager.sendPayment(amount = amount, rawText = "ALIPAY|$title")
                    LogManager.addLog("MQTT", "发送完成")
                }

                // 处理微信
                WECHAT_PACKAGE -> {
                    LogManager.addLog("微信收款通知", "title=$title | text=$text")

                    // 从text中提取金额："微信支付收款0.01元(老顾客第50次消费)" → 0.01
                    val matcher = WECHAT_AMOUNT_PATTERN.matcher(text)
                    if (!matcher.find()) {
                        LogManager.addLog("微信解析失败", "text中没找到金额")
                        return
                    }

                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 微信解析成功", "金额:¥$amount")

                    // 发送给PC端
                    LogManager.addLog("MQTT", "正在发送微信金额${amount}到PC端...")
                    MqttClientManager.sendPayment(amount = amount, rawText = "WECHAT|$text")
                    LogManager.addLog("MQTT", "发送完成")
                }
            }


        } catch (e: Exception) {
            LogManager.addLog("❌ 异常", e.message ?: "未知错误")
            Log.e(TAG, "处理通知异常", e)
        }
    }
}
