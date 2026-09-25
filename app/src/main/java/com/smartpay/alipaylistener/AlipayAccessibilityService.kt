package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AlipayAccessibilityService : AccessibilityService() {
    companion object {
        private var instance: AlipayAccessibilityService? = null
        fun manualDumpNow() {
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkg = event.packageName?.toString() ?: return
            // 只打印微信的事件
            if (pkg != "com.tencent.mm") return

            // 打印事件基本信息
            val eventType = when(event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口状态变化"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "窗口内容变化"
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "文本变化"
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> "滚动"
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "通知状态变化"
                else -> "类型${event.eventType}"
            }
            LogManager.addLog("微信事件", "[$eventType] class=${event.className}")

            // 打印事件自带的文本
            val eventText = event.text.joinToString(" | ") { it.toString() }
            if (eventText.isNotEmpty()) {
                LogManager.addLog("微信事件", "事件文本: $eventText")
            }
            val eventDesc = event.contentDescription?.toString() ?: ""
            if (eventDesc.isNotEmpty()) {
                LogManager.addLog("微信事件", "事件描述: $eventDesc")
            }

            // 打印事件source节点的内容
            val source = event.source
            if (source != null) {
                val sb = StringBuilder()
                traverseNode(source, sb, 1)
                if (sb.isNotEmpty()) {
                    LogManager.addLog("微信事件", "source节点内容:\n$sb")
                }
                source.recycle()
            }
        } catch (e: Exception) {
            LogManager.addLog("微信事件异常", e.message ?: "未知错误")
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 20) return
        try {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val cls = node.className?.toString() ?: ""
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                sb.appendLine("  ".repeat(depth) + "[$cls] text='$text' desc='$desc'")
            }
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), sb, depth + 1)
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}
