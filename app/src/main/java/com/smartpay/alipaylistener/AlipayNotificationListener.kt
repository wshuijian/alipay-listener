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

        // 第一阶段：只做通知链路诊断，不执行金额解析，也不触发 MQTT。
        // 确认能稳定拿到支付宝到账原始 Notification 后，再切回 false 进入第二阶段。
        private const val DIAGNOSTICS_ONLY = true

        private val AMOUNT_PATTERN = Pattern.compile("([\\d]+\\.?[\\d]*)\\s*元")
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

        logNotificationPosted(sbn)

        // 调试阶段到此为止，不进入金额解析/MQTT 流程，避免多个问题混在一起。
        if (!DIAGNOSTICS_ONLY) {
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

        val summary = buildNotificationSummary(sbn, "POST")

        LogManager.addLog("通知POST", summary)
        Log.i(TAG, "onNotificationPosted\n$summary")

        if (sbn.packageName == ALIPAY_PACKAGE) {
            LogManager.addLog("支付宝POST", "收到支付宝通知，输出完整 extras")
            Log.i(TAG, "Alipay notification posted, dumping extras")
            dumpExtras(extras, "支付宝POST extras")
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
     * 第二阶段逻辑保留，但第一阶段由 DIAGNOSTICS_ONLY 拦截，不执行。
     * 处理通知（新通知和更新通知都走这里）
     */
    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getString(Notification.EXTRA_TITLE, "")
            val text = extras.getString(Notification.EXTRA_TEXT, "")
            val bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "")

            val fullText = "$title $text $bigText"

            LogManager.addLog("收到通知", "包名:$packageName | 标题:$title | 内容:$text")

            // 只处理支付宝的通知
            if (packageName != ALIPAY_PACKAGE) {
                return
            }

            // 先排除付款方/待付款的通知
            if (isPayerNotification(fullText)) {
                LogManager.addLog("判断结果", "是付款方/待付款通知，忽略")
                return
            }

            // 判断是否是收款通知
            if (!isPaymentNotification(fullText)) {
                LogManager.addLog("判断结果", "不是收款通知，忽略")
                return
            }

            // 提取金额
            val amount = extractAmount(fullText)
            if (amount == null) {
                LogManager.addLog("判断结果", "无法提取金额，忽略")
                return
            }

            LogManager.addLog("✅ 检测到收款", "金额:¥$amount")

            // 发送给PC端（MQTT全网通）
            MqttClientManager.sendPayment(amount = amount, rawText = fullText)

        } catch (e: Exception) {
            LogManager.addLog("❌ 异常", e.message ?: "未知错误")
            Log.e(TAG, "处理通知异常", e)
        }
    }

    /**
     * 判断是否是付款方/待付款的通知（排除这些）
     */
    private fun isPayerNotification(text: String): Boolean {
        val payerKeywords = listOf(
            "付款成功", "支付成功", "正在付款", "付款中",
            "转账成功", "已付款", "已支付", "消费",
            "支出", "花呗", "账单", "还款",
            "待收款", "等待付款", "待支付", "付款待确认",
            "等待收款", "待确认", "处理中", "支付处理中"
        )
        return payerKeywords.any { text.contains(it) }
    }

    /**
     * 判断是否是收款通知（先排除待付款，再判断收款关键词）
     */
    private fun isPaymentNotification(text: String): Boolean {
        val pendingKeywords = listOf(
            "待收款", "等待付款", "待支付", "付款待确认",
            "等待收款", "待确认", "处理中", "支付处理中",
            "正在付款", "付款中"
        )
        if (pendingKeywords.any { text.contains(it) }) {
            return false
        }

        val receiveKeywords = listOf(
            "收款", "到账", "已收款", "收款成功",
            "你有一笔", "收钱码", "收到转账", "转账到账",
            "余额收款", "商家收款", "二维码收款"
        )
        return receiveKeywords.any { text.contains(it) }
    }

    /**
     * 从文本中提取金额
     */
    private fun extractAmount(text: String): String? {
        val matcher = AMOUNT_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }
}
