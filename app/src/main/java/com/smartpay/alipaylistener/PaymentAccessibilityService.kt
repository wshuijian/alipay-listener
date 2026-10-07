package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

/**
 * Unified accessibility payment listener.
 * First implementation targets 安徽农金 e信付.
 */
class PaymentAccessibilityService : AccessibilityService() {

    private var tts: TextToSpeech? = null
    private var lastAmount = ""

    override fun onServiceConnected() {
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
            }
        }
        LogManager.addLog("【支付服务】", "统一支付无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.sunyard.arcu2b") return

        val text = event.text.joinToString(" ")
        val amount = Regex("([0-9]+\\.?[0-9]*)元").find(text)?.groupValues?.get(1)

        if (!amount.isNullOrEmpty() && amount != lastAmount) {
            lastAmount = amount
            val message = "安徽农金收款${amount}元"
            LogManager.addLog("【支付到账】", message)
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "payment")
        }
    }

    override fun onInterrupt() {
        LogManager.addLog("【支付服务】", "服务中断")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
