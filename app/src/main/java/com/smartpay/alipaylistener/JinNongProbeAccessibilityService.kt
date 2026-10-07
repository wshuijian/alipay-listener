package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 金农e信付正式收款识别服务
 * Commit 5:
 * 读取固定金额节点 text_pay_money
 * +0.01元 -> 0.01
 * TTS: 安徽农金收款0.01元
 */
class JinNongProbeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TARGET_PACKAGE = "com.sunyard.arcu2b"
        private const val MONEY_NODE_ID = "com.sunyard.arcu2b:id/text_pay_money"
    }

    private var tts: TextToSpeech? = null
    private var lastAmount = ""
    private var lastSpeakTime = 0L

    override fun onServiceConnected() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
        LogManager.addLog("【金农】", "正式收款识别服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        try {
            findMoneyNode(rootInActiveWindow)?.let { raw ->
                val amount = parseAmount(raw) ?: return
                val now = System.currentTimeMillis()

                if (amount == lastAmount && now - lastSpeakTime < 5000) return

                lastAmount = amount
                lastSpeakTime = now

                val text = "安徽农金收款${amount}元"
                LogManager.addLog("【金农收款】", text)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jinnong_payment")
            }
        } catch (e: Exception) {
            LogManager.addLog("【金农异常】", e.message ?: "unknown")
        }
    }

    private fun findMoneyNode(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null

        if (node.viewIdResourceName == MONEY_NODE_ID) {
            return node.text?.toString()
        }

        for (i in 0 until node.childCount) {
            val result = findMoneyNode(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    private fun parseAmount(text: String): String? {
        val match = Regex("[0-9]+(\\.[0-9]+)?").find(text) ?: return null
        return match.value
    }

    override fun onInterrupt() {
        LogManager.addLog("【金农】", "服务中断")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
