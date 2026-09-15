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
        if (packageName != ALIPAY_PACKAGE) return

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getString(Notification.EXTRA_TITLE, "")
            val text = extras.getString(Notification.EXTRA_TEXT, "")
            val bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "")

            val fullText = "$title $text $bigText"

            if (isPayerNotification(fullText)) {
                Log.d(TAG, "是付款方/待付款通知，忽略: $fullText")
                return
            }

            if (!isPaymentNotification(fullText)) {
                Log.d(TAG, "不是收款通知，忽略: $fullText")
                return
            }

            val amount = extractAmount(fullText) ?: return

            val eventId = "${sbn.key}_${sbn.postTime}"
            if (processedIds.contains(eventId)) return
            processedIds.add(eventId)
            if (processedIds.size > 500) processedIds.clear()

            Log.i(TAG, "检测到支付宝收款: ¥$amount, 内容: $fullText")
            MqttClientManager.sendPayment(amount = amount, rawText = fullText)

        } catch (e: Exception) {
            Log.e(TAG, "处理通知异常", e)
        }
    }

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

    private fun isPaymentNotification(text: String): Boolean {
        val receiveKeywords = listOf(
            "已收款", "收款成功", "收款到账", "到账成功",
            "你有一笔收款", "收钱码收款", "二维码收款",
            "商家收款", "余额收款", "收到转账", "转账到账",
            "收款¥", "到账¥", "已成功收款"
        )
        return receiveKeywords.any { text.contains(it) }
    }

    private fun extractAmount(text: String): String? {
        val matcher = AMOUNT_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "通知监听服务已断开")
    }
}
