package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JinNongProbeAccessibilityService : AccessibilityService() {
    // 模糊匹配金农相关包名，不写死具体包名
    private val TARGET_KEYWORDS = listOf(
        "金农",
        "e信付",
        "jnn",
        "农金",
        "ahrcbank"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        // 模糊匹配包名，命中目标关键词才打印
        var isTarget = false
        for (kw in TARGET_KEYWORDS) {
            if (pkg.contains(kw, ignoreCase = true)) {
                isTarget = true
                break
            }
        }
        if (!isTarget) return

        // 打印完整事件基础信息
        val eventTypeStr = when(event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口切换"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "内容变化"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "文本变化"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "页面滚动"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "点击事件"
            else -> "其他事件:${event.eventType}"
        }
        LogManager.addLog("【金农探针2.0】", "包名:$pkg 事件类型:$eventTypeStr 事件文本:${event.text}")

        // 获取源节点，递归遍历打印
        val sourceNode = event.source ?: rootInActiveWindow ?: return
        traverseNode(sourceNode, 0)
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        try {
            val className = node.className?.toString() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val childCount = node.childCount

            // 检测WebView
            if (className.contains("WebView")) {
                LogManager.addLog("【发现WebView】", "包名:${packageName} 类名:$className")
            }

            // 只打印有实际内容的节点
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                LogManager.addLog("【金农探针2.0】", "类:$className ID:$viewId 子节点数:$childCount 文本:[$text] 描述:[$desc]")
            }

            // 递归遍历子节点
            for (i in 0 until childCount) {
                traverseNode(node.getChild(i), depth + 1)
            }
        } catch (e: Exception) {
            // 节点访问异常直接跳过，不崩溃
        }
    }

    override fun onInterrupt() {
        // 调试服务不需要处理中断
    }
}
