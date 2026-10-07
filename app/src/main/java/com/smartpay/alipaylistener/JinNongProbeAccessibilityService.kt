package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JinNongProbeAccessibilityService : AccessibilityService() {
    // 金农e信付相关包名匹配，后续根据实际调试结果补全
    private val TARGET_PACKAGES = listOf(
        "com.ahrcbank.jinnongexinfu",
        "com.ahrcbank.mpay",
        "com.kuaiyin.micropayassistant"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 只监听窗口状态变化和内容变化事件
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // 只处理目标相关包名
        var isTarget = false
        for (targetPkg in TARGET_PACKAGES) {
            if (pkg.contains(targetPkg)) {
                isTarget = true
                break
            }
        }
        if (!isTarget) return
        // 递归遍历所有节点，打印非空文本
        val rootNode = rootInActiveWindow ?: return
        LogManager.addLog("【金农探针】", "前台包名:$pkg 事件类型:${if(event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) "窗口切换" else "内容变化"}")
        traverseNode(rootNode, 0)
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, depth: Int) {
        node ?: return
        try {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            // 只打印有实际文字内容的节点
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                LogManager.addLog("【金农探针】", "类:${node.className} ID:${node.viewIdResourceName} 文本:[$text] 描述:[$desc]")
            }
            // 递归遍历子节点
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), depth + 1)
            }
        } catch (e: Exception) {
            // 节点访问异常直接跳过，不崩溃
        }
    }

    override fun onInterrupt() {
        // 不需要处理中断
    }
}
