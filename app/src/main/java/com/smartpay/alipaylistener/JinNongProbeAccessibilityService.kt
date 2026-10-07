package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 金农e信付页面读取探针 - 第一阶段裸监听版
 *
 * 目的：先确认 Android Accessibility Framework 是否有事件进入。
 * 暂时去掉包名过滤和节点递归，避免因为过滤条件导致完全无日志。
 */
class JinNongProbeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        LogManager.addLog(
            "【金农探针】",
            "无障碍服务已连接"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: "null"
        val cls = event.className?.toString() ?: "null"

        LogManager.addLog(
            "【A11Y原始事件】",
            """
            package=$pkg
            type=${event.eventType}
            class=$cls
            text=${event.text}
            """.trimIndent()
        )

        try {
            val root = rootInActiveWindow

            LogManager.addLog(
                "【A11Y ROOT】",
                """
                rootClass=${root?.className}
                childCount=${root?.childCount}
                rootText=${root?.text}
                """.trimIndent()
            )

            if (root?.className?.toString()?.contains("WebView", true) == true) {
                LogManager.addLog(
                    "【发现WebView】",
                    "class=${root.className}"
                )
            }
        } catch (e: Exception) {
            LogManager.addLog(
                "【A11Y异常】",
                e.message ?: "unknown"
            )
        }
    }

    override fun onInterrupt() {
        LogManager.addLog(
            "【金农探针】",
            "服务中断"
        )
    }
}
