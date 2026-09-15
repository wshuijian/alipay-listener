package com.smartpay.alipaylistener

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.UUID
import java.util.regex.Pattern

class AlipayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AlipayListener"
        // 支付宝包名
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        // 匹配金额的正则
        private val AMOUNT_PATTERN = Pattern.compile("([\\d]+\\.?[\\d]*)\\s*元")
        // 已处理的通知ID（去重）
        private val processedIds = mutableSetOf<String>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null) return

        val packageName = sbn.packageName
        Log.d(TAG, "收到通知: $packageName")

        // 只处理支付宝的通知
        if (packageName != ALIPAY_PACKAGE) {
            return
        }

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getString(Notification.EXTRA_TITLE, "")
            val text = extras.getString(Notification.EXTRA_TEXT, "")
            val bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "")

            Log.d(TAG, "支付宝通知 - title: $title, text: $text, bigText: $bigText")

            // 合并所有文本
            val fullText = "$title $text $bigText"

            // 判断是否是收款通知
            if (!isPaymentNotification(fullText)) {
                Log.d(TAG, "不是收款通知，忽略")
                return
            }

            // 提取金额
            val amount = extractAmount(fullText)
            if (amount == null) {
                Log.d(TAG, "无法提取金额，忽略")
                return
            }

            // 生成唯一事件ID（用通知key+时间去重）
            val eventId = "${sbn.key}_${sbn.postTime}"
            if (processedIds.contains(eventId)) {
                Log.d(TAG, "重复通知，忽略")
                return
            }
            processedIds.add(eventId)
            // 限制集合大小
            if (processedIds.size > 500) {
                processedIds.clear()
            }

            Log.i(TAG, "检测到支付宝收款: ¥$amount")

            // 发送给PC端（MQTT全网通）
            MqttClientManager.sendPayment(
                amount = amount,
                rawText = fullText
            )

        } catch (e: Exception) {
            Log.e(TAG, "处理通知异常", e)
        }
    }

    /**
     * 判断是否是收款通知
     */
    private fun isPaymentNotification(text: String): Boolean {
        val keywords = listOf(
            "收款", "到账", "收到", "付款成功", "支付成功",
            "收钱码", "收款码", "已收款", "收款成功",
            "收到转账", "转账到账", "余额收款"
        )
        return keywords.any { text.contains(it) }
    }

    /**
     * 从文本中提取金额
     */
    private fun extractAmount(text: String): String? {
        val matcher = AMOUNT_PATTERN.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
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
