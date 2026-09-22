package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * 无障碍服务 - 精准诊断SystemUI里的通知内容
 */
class AlipayAccessibilityService : AccessibilityService() {
    private val keywords = listOf("邮付", "收款", "到账", "¥", "￥", "元")
    private val lastTexts = mutableSetOf<String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val windows = windows ?: return
            val nowTexts = mutableSetOf<String>()
            for (window in windows) {
                val rootNode = window.root ?: continue
                val pkg = rootNode.packageName?.toString() ?: continue
                // 只看SystemUI窗口，其他窗口都跳过
                if (!pkg.contains("systemui") && !pkg.contains("android")) continue
                // 递归读所有文本
                val texts = mutableListOf<String>()
                traverseNode(rootNode, texts, 0)
                texts.forEach { t ->
                    nowTexts.add(t)
                    // 遇到关键词才打印
                    if (keywords.any { t.contains(it) }) {
                        LogManager.addLog("SystemUI通知诊断", t)
                    }
                }
            }
            lastTexts.clear()
            lastTexts.addAll(nowTexts)
        } catch (e: Exception) {
            LogManager.addLog("无障碍诊断异常", e.message ?: "未知错误")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, texts: MutableList<String>, depth: Int) {
        if (node == null || depth > 20) return
        try {
            if (!node.text.isNullOrEmpty()) texts.add(node.text.toString())
            if (!node.contentDescription.isNullOrEmpty()) texts.add(node.contentDescription.toString())
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), texts, depth + 1)
            }
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {}
}