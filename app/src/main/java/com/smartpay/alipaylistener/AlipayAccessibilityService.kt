package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.*

/**
 * 无障碍服务 - 被动接收事件流，直接读事件自带的文本，不主动遍历窗口
 */
class AlipayAccessibilityService : AccessibilityService() {
    private val amountRegex = Regex("(\\d+(\\.\\d{1,2})?)\\s*元")
    private var lastAmountTime = 0L
    private var lastAmount = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkg = event.packageName?.toString() ?: return
            // 只处理微信的事件
            if (pkg != "com.tencent.mm") return
            // 遍历事件自带的所有文本，不主动遍历任何窗口
            event.text.forEach { text ->
                val t = text.toString()
                LogManager.addLog("无障碍事件文本", t)
                // 匹配金额
                val match = amountRegex.find(t)
                if (match != null && t.contains("收款")) {
                    val amount = match.groupValues[1]
                    // 10秒内同金额不重复播报
                    val now = System.currentTimeMillis()
                    if (amount != lastAmount || now - lastAmountTime > 10000) {
                        lastAmount = amount
                        lastAmountTime = now
                        LogManager.addLog("✅ 无障碍抓到邮付金额", "¥$amount")
                        // 直接走现有MQTT推送
                        MqttClientManager.sendPayment(amount = amount, rawText = "YOUFU|$t")
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("无障碍异常", e.message ?: "未知错误")
        }
    }

    override fun onInterrupt() {}
}