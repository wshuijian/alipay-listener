package com.smartpay.alipaylistener

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.regex.Pattern

class AlipayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AlipayListener"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private val AMOUNT_PATTERN = Pattern.compile("([\\d]+\\.?[\\d]*)\\s*元")
        private val processedIds = mutableSetOf<String>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName

        // 【调试模式】先打印所有通知，确认服务是否正常
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

            // 去重
            val eventId = "${sbn.key}_${sbn.postTime}"
            if (processedIds.contains(eventId)) {
                LogManager.addLog("判断结果", "重复通知，忽略")
                return
            }
            processedIds.add(eventId)
            if (processedIds.size > 500) processedIds.clear()

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
        // 第一步：排除明确的待付款/付款中关键词
        val pendingKeywords = listOf(
            "待收款", "等待付款", "待支付", "付款待确认",
            "等待收款", "待确认", "处理中", "支付处理中",
            "正在付款", "付款中"
        )
        if (pendingKeywords.any { text.contains(it) }) {
            return false
        }

        // 第二步：判断是否包含收款关键词（宽松一点，只要有收款或到账就行）
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogManager.addLog("系统", "通知监听服务已连接")
        Log.i(TAG, "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogManager.addLog("系统", "通知监听服务已断开")
        Log.i(TAG, "通知监听服务已断开")
    }
}
