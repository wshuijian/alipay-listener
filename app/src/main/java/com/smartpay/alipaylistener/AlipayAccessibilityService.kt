package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

/**
 * 无障碍服务 - 读取支付宝屏幕内容，识别收款通知
 * 这是收款播报软件的标准实现方式
 */
class AlipayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AlipayAccessibility"
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private val AMOUNT_PATTERN = Pattern.compile("([\\d]+\\.?[\\d]*)\\s*元")
        private val processedTexts = mutableSetOf<String>()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 只处理支付宝的事件
        if (event.packageName != ALIPAY_PACKAGE) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    handleEvent(event)
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("无障碍异常", e.message ?: "未知错误")
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val node = rootInActiveWindow ?: return

        // 递归遍历所有节点，收集文本
        val texts = mutableListOf<String>()
        collectTexts(node, texts)

        val fullText = texts.joinToString(" ")

        // 只打印支付宝相关的通知
        if (fullText.contains("收款") || fullText.contains("到账") || fullText.contains("元")) {
            LogManager.addLog("无障碍", "屏幕内容: $fullText")

            // 判断是否是收款成功
            if (isPaymentSuccess(fullText)) {
                val amount = extractAmount(fullText)
                if (amount != null) {
                    // 去重
                    val eventId = "${amount}_${fullText.hashCode()}"
                    if (processedTexts.contains(eventId)) {
                        return
                    }
                    processedTexts.add(eventId)
                    if (processedTexts.size > 500) processedTexts.clear()

                    LogManager.addLog("✅ 检测到收款", "金额:¥$amount")
                    MqttClientManager.sendPayment(amount = amount, rawText = fullText)
                }
            }
        }
    }

    /**
     * 递归收集所有文本
     */
    private fun collectTexts(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return

        node.text?.let {
            texts.add(it.toString())
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectTexts(child, texts)
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
            "元已到账", "元到账"
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
