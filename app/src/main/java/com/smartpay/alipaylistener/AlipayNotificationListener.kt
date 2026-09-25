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

        private const val DIAGNOSTICS_ONLY = false

        private val processedKeys = mutableSetOf<String>()
        private val seenNotificationKeys = mutableSetOf<String>()

        private val ALIPAY_AMOUNT_PATTERN = Pattern.compile("你已成功收款([\\d]+\\.?[\\d]*)元")
        private val WECHAT_AMOUNT_PATTERN = Pattern.compile("微信支付收款([\\d]+\\.?[\\d]*)元")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogManager.addLog("系统", "通知监听服务已连接")
        dumpActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogManager.addLog("系统", "通知监听服务已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val isNewPost = !seenNotificationKeys.contains(sbn.key)
        val now = System.currentTimeMillis()
        val timeDiff = now - sbn.postTime

        logNotificationPosted(sbn)

        if (!DIAGNOSTICS_ONLY) {
            if (timeDiff > 10000) return
            processNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == WECHAT_PACKAGE) {
            val title = sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE, "")?.toString() ?: ""
            if (title == "邮付小助手") {
                LogManager.addLog("邮付诊断", "REMOVE事件 | key=${sbn.key} | title=$title")
            }
        }
        logNotificationRemoved(sbn, null)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        if (sbn == null) return
        logNotificationRemoved(sbn, reason)
    }

    private fun logNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification?.extras

        val event: String
        if (seenNotificationKeys.contains(sbn.key)) {
            event = "UPDATE"
        } else {
            event = "POST"
            seenNotificationKeys.add(sbn.key)
        }

        val summary = buildNotificationSummary(sbn, event)
        LogManager.addLog("通知$event", summary)

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
            appendLine("title=$title")
            appendLine("text=$text")
            appendLine("bigText=$bigText")
        }.trimEnd()

        LogManager.addLog("通知REMOVE", summary)
    }

    private fun dumpActiveNotifications() {
        try {
            val active = getActiveNotifications()
            val count = active?.size ?: 0
            LogManager.addLog("系统", "activeNotifications 数量=$count")
            if (active == null || active.isEmpty()) return
            active.forEach { sbn ->
                val summary = buildNotificationSummary(sbn, "ACTIVE")
                LogManager.addLog("通知ACTIVE", summary)
            }
        } catch (e: Exception) {
            LogManager.addLog("系统", "读取activeNotifications异常: ${e.message}")
        }
    }

    private fun buildNotificationSummary(sbn: StatusBarNotification, source: String): String {
        val notification = sbn.notification
        val extras = notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        return buildString {
            appendLine("source=$source")
            appendLine("packageName=${sbn.packageName}")
            appendLine("title=$title")
            appendLine("text=$text")
        }.trimEnd()
    }

    private fun formatTime(millis: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE, "")?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT, "")?.toString() ?: ""

            val eventKey = "${sbn.key}_${sbn.postTime}"
            if (processedKeys.contains(eventKey)) return
            processedKeys.add(eventKey)
            if (processedKeys.size > 100) processedKeys.clear()

            when (packageName) {
                ALIPAY_PACKAGE -> {
                    val channelId = notification.channelId ?: ""
                    if (channelId != ALIPAY_PAY_CHANNEL) return
                    val matcher = ALIPAY_AMOUNT_PATTERN.matcher(title)
                    if (!matcher.find()) return
                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 支付宝", "金额:¥$amount")
                    MqttClientManager.sendPayment(amount = amount, rawText = "ALIPAY|$title")
                }
                WECHAT_PACKAGE -> {
                    val matcher = WECHAT_AMOUNT_PATTERN.matcher(text)
                    if (!matcher.find()) return
                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 微信", "金额:¥$amount")
                    MqttClientManager.sendPayment(amount = amount, rawText = "WECHAT|$text")
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("❌ 异常", e.message ?: "未知错误")
        }
    }
}
