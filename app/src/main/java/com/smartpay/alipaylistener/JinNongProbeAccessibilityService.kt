package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 金农e信付页面读取探针 - 第二阶段节点分析版
 *
 * 目标：
 * 1. 降低日志噪音，只关注金农信e付
 * 2. 扫描Accessibility节点，寻找金额文本
 */
class JinNongProbeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TARGET_PACKAGE = "com.sunyard.arcu2b"
        private const val TAG = "【金农节点】"
        private val KEYWORDS = listOf(
            "元", "￥", "¥", "收款", "到账", "成功", "金额"
        )
    }

    private var lastLogText = ""
    private var lastLogTime = 0L

    override fun onServiceConnected() {
        LogManager.addLog(
            "【金农探针】",
            "节点分析服务已连接"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg != TARGET_PACKAGE) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }

        try {
            rootInActiveWindow?.let {
                scanNode(it, 0)
            }
        } catch (e: Exception) {
            LogManager.addLog(
                "【A11Y异常】",
                e.message ?: "unknown"
            )
        }
    }

    private fun scanNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 6) return

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName.orEmpty()

        val content = listOf(text, desc, id)
            .filter { it.isNotEmpty() }
            .joinToString(" | ")

        if (content.isNotEmpty() && KEYWORDS.any { content.contains(it) }) {
            val now = System.currentTimeMillis()
            if (content != lastLogText || now - lastLogTime > 5000) {
                lastLogText = content
                lastLogTime = now

                LogManager.addLog(
                    TAG,
                    """
                    class=${node.className}
                    text=$text
                    desc=$desc
                    id=$id
                    """.trimIndent()
                )
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                scanNode(it, depth + 1)
            }
        }
    }

    override fun onInterrupt() {
        LogManager.addLog(
            "【金农探针】",
            "服务中断"
        )
    }
}
