package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.util.regex.Pattern

/**
 * 无障碍服务 - 监听通知事件，识别支付宝收款通知
 */
class AlipayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AlipayAccessibility"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private val AMOUNT_PATTERN = Pattern.compile("([\\d]+\\.?[\\d]*)\\s*元")
        private val processedIds = mutableSetOf<String>()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            // 只处理通知状态变化事件
            if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                return
            }

            val packageName = event.packageName?.toString() ?: return

            // 只处理支付宝的通知
            if (packageName != ALIPAY_PACKAGE) {
                return
            }

            // 从事件里提取通知文本
            val texts = event.text ?: return
            if (texts.isEmpty()) return

            val fullText = texts.joinToString(" ")
            LogManager.addLog("无障碍收到通知", "$fullText")

            // 判断是否是收款成功
            if (isPaymentSuccess(fullText)) {
                val amount = extractAmount(fullText)
                if (amount != null) {
                    // 去重
                    val eventId = "${amount}_${fullText.hashCode()}"
                    if (processedIds.contains(eventId)) {
                        return
                    }
                    processedIds.add(eventId)
                    if (processedIds.size > 500) processedIds.clear()

                    LogManager.addLog("✅ 检测到收款", "金额:¥$amount")
                    MqttClientManager.sendPayment(amount = amount, rawText = fullText)
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("无障碍异常", e.message ?: "未知错误")
        }
    }

    /**
     * 判断是否是收款成功
     */
    private fun isPaymentSuccess(text: String): Boolean {
        // 先排除待付款
        val pendingKeywords = listOf(
            "待收款", "等待付款", "待支付", "付款待确认",
            "等待收款", "待确认", "处理中", "支付处理中",
            "正在付款", "付款中"
        )
        if (pendingKeywords.any { text.contains(it) }) {
            return false
        }

        // 判断收款关键词
        val receiveKeywords = listOf(
            "收款成功", "已收款", "收款到账",
            "你有一笔", "收到转账", "转账到账",
            "余额收款", "商家收款", "二维码收款",
            "元已到账", "元到账", "收钱"
        )
        return receiveKeywords.any { text.contains(it) }
    }

    /**
     * 提取金额
     */
    private fun extractAmount(text: String): String? {
        val matcher = AMOUNT_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    override fun onInterrupt() {
        LogManager.addLog("无障碍", "服务中断")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogManager.addLog("✅ 无障碍服务已连接", "无障碍服务启动成功")
    }
}
