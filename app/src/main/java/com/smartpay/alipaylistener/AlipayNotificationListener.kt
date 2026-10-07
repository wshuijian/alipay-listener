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
        private const val ICBC_PACKAGE = "com.icbc"
        private const val WEIPAY_ASSISTANT_PACKAGE = "com.kuaiyin.micropayassistant"
        private const val ALIPAY_PAY_CHANNEL = "alipay_default"

        private const val DIAGNOSTICS_ONLY = false

        private val processedKeys = mutableSetOf<String>()
        private val seenNotificationKeys = mutableSetOf<String>()

        private val ALIPAY_AMOUNT_PATTERN = Pattern.compile("你已成功收款([\\d]+\\.?[\\d]*)元")
        private val WECHAT_AMOUNT_PATTERN = Pattern.compile("微信支付收款([\\d]+\\.?[\\d]*)元")
        private val ICBC_AMOUNT_PATTERN = Pattern.compile("收入.*?([\\d]+\\.?[\\d]*)元")
        private val WEIPAY_ASSISTANT_AMOUNT_PATTERN = Pattern.compile("微邮付收款([\\d]+\\.?[\\d]*)元")
        // 通用银行收款规则配置
        private val GENERIC_AMOUNT_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]{1,2})?)元")
        private val POSITIVE_KEYWORDS = listOf("收入", "收款到账", "收款", "入账", "存入")
        private val NEGATIVE_KEYWORDS = listOf("支出", "消费", "扣费", "还款", "转出", "转账出", "付款", "缴费", "退款", "优惠券", "红包", "奖励")
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
            // 仅新POST通知做10秒延迟过滤，已识别过的通知UPDATE不受10秒限制
            if (isNewPost && timeDiff > 10000) return
            processNotification(sbn, isNewPost)
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

        // ===== 邮付小助手全字段深度dump =====
        if (sbn.packageName == WECHAT_PACKAGE) {
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE, "")?.toString() ?: ""
            if (title.contains("邮付小助手")) {
                LogManager.addLog("邮付深度dump", "===== 开始深度dump邮付通知 =====")
                extras?.keySet()?.forEach { k ->
                    val v = when(val value = extras.get(k)) {
                        null -> "null"
                        is CharSequence -> value.toString()
                        is Array<*> -> value.contentToString()
                        else -> value.javaClass.simpleName + "=" + value.toString().take(100)
                    }
                    LogManager.addLog("邮付深度dump", "extras[$k] = $v")
                }
                LogManager.addLog("邮付深度dump", "跳过MessagingStyle检查，避免编译错误")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pub = notification?.publicVersion
                        if (pub != null) {
                            LogManager.addLog("邮付深度dump", "publicVersion: title=${pub.extras.getCharSequence(Notification.EXTRA_TITLE)} bigText=${pub.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)}")
                        } else {
                            LogManager.addLog("邮付深度dump", "无publicVersion")
                        }
                    }
                } catch (e: Exception) {
                    LogManager.addLog("邮付深度dump", "publicVersion解析失败: ${e.message}")
                }
                LogManager.addLog("邮付深度dump", "===== 深度dump结束 =====")
            }
        }
        // ===== 深度dump结束 =====

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

    private fun processNotification(sbn: StatusBarNotification, isNewPost: Boolean) {
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
                    // 普通支付宝收款只在新POST处理，UPDATE不重复发
                    if (!isNewPost) return
                    val channelId = notification.channelId ?: ""
                    if (channelId != ALIPAY_PAY_CHANNEL) return
                    val matcher = ALIPAY_AMOUNT_PATTERN.matcher(title)
                    if (!matcher.find()) return
                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 支付宝", "金额:¥$amount")
                    MqttClientManager.sendPayment(amount = amount, rawText = "ALIPAY|$title", source = "ALIPAY")
                }
                WECHAT_PACKAGE -> {
                    // 检测到邮付小助手收款通知，不管是POST还是UPDATE（微信聚合通知）都触发bank_trigger
                    if (title == "邮付小助手" && text.contains("收款到账通知")) {
                        LogManager.addLog("🔔 邮付触发", "检测到邮付收款通知，发送拉单触发到PC")
                        MqttClientManager.sendWeipayTrigger(appName = "邮付小助手")
                        return
                    }
                    if (title == "安徽农金云收单" && text.contains("收款到账通知")) {
                        LogManager.addLog("🔔 农金触发", "检测到安徽农金收款通知，发送拉单触发到PC")
                        MqttClientManager.sendWeipayTrigger(appName = "安徽农金")
                        return
                    }
                    // 微信POST和UPDATE都进入金额解析流程，靠现有processedKeys去重避免重复播报
                    // 先去掉通知聚合前缀：[2条] [3条] 等
                    var cleanText = text.replace(Regex("^\\[\\d+条\\]"), "").trim()
                    val matcher = WECHAT_AMOUNT_PATTERN.matcher(cleanText)
                    if (!matcher.find()) return
                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 微信", "金额:¥$amount 通知类型=${if(isNewPost) "POST" else "UPDATE"}")
                    MqttClientManager.sendPayment(amount = amount, rawText = "WECHAT|$cleanText", source = "WECHAT")
                }
                ICBC_PACKAGE -> {
                    // 工商银行动账通知解析
                    if (title != "动账通知" || !text.contains("收入")) return
                    val matcher = ICBC_AMOUNT_PATTERN.matcher(text)
                    if (!matcher.find()) return
                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 工商银行", "收入金额:¥$amount")
                    MqttClientManager.sendPayment(amount = amount, rawText = "ICBC|$text", source = "ICBC")
                }
                WEIPAY_ASSISTANT_PACKAGE -> {
                    // 邮付助理APP本地通知解析
                    if (title != "邮付小助手") return
                    val matcher = WEIPAY_ASSISTANT_AMOUNT_PATTERN.matcher(text)
                    if (!matcher.find()) return
                    val amount = matcher.group(1)
                    LogManager.addLog("✅ 邮付助理", "收款金额:¥$amount")
                    MqttClientManager.sendPayment(amount = amount, rawText = "WEIPAY_ASSISTANT|$text", source = "WEIPAY_ASSISTANT")
                }
            }

            // ===== 通用银行/收款通知规则（所有专用规则走完后才执行） =====
            // 排除微信和支付宝包，避免和原有逻辑重复识别
            if (packageName == WECHAT_PACKAGE || packageName == ALIPAY_PACKAGE) return
            // 1. 先检查负向关键词，命中直接跳过
            for (neg in NEGATIVE_KEYWORDS) {
                if (text.contains(neg)) {
                    return
                }
            }
            // 2. 检查是否包含正向收款关键词
            var hasPositive = false
            for (pos in POSITIVE_KEYWORDS) {
                if (text.contains(pos)) {
                    hasPositive = true
                    break
                }
            }
            if (!hasPositive) return
            // 3. 提取金额
            val genericMatcher = GENERIC_AMOUNT_PATTERN.matcher(text)
            if (!genericMatcher.find()) return
            val amount = genericMatcher.group(1)
            // 4. 渠道名优先用通知标题，标题空就用包名兜底
            val channelName = title.ifEmpty { packageName }
            LogManager.addLog("✅ 通用银行", "[$channelName] 识别到收款金额:¥$amount 原文: $text")
            MqttClientManager.sendPayment(amount = amount, rawText = "GENERIC_BANK|$text", source = channelName)
            // ===== 通用规则结束 =====
        } catch (e: Exception) {
            LogManager.addLog("❌ 异常", e.message ?: "未知错误")
        }
    }
}


