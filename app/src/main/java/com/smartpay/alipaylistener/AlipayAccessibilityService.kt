package com.smartpay.alipaylistener

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * 无障碍服务 - 纯诊断模式：只读支付宝/微信页面文本，不做任何业务判断
 * 目的：验证能不能读到支付页面、取消支付、收款状态的文本
 * 不做自动点击、不修改页面、不发送MQTT
 */
class AlipayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "无障碍诊断"
        private val TARGET_PACKAGES = setOf(
            "com.eg.android.AlipayGphone", // 支付宝
            "com.tencent.mm"                // 微信
        )
        private val lastLogMap = mutableMapOf<String, Long>()
        private const val DEDUP_INTERVAL = 2000L // 2秒内相同内容不重复打日志
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val packageName = event.packageName?.toString() ?: return
            // 只处理支付宝和微信的事件
            if (packageName !in TARGET_PACKAGES) return

            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
            val eventTypeStr = when(event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口切换"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "内容变化"
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "通知变化"
                else -> "其他事件(${event.eventType})"
            }

            // 遍历根节点收集所有可读文本
            val rootNode = rootInActiveWindow ?: return
            val texts = mutableListOf<String>()
            traverseNode(rootNode, texts, depth = 0)

            // 2秒去重
            val contentKey = texts.joinToString("|").hashCode().toString()
            val lastTime = lastLogMap[contentKey] ?: 0L
            if (System.currentTimeMillis() - lastTime < DEDUP_INTERVAL) {
                return
            }
            lastLogMap[contentKey] = System.currentTimeMillis()
            if (lastLogMap.size > 200) lastLogMap.clear()

            // 写日志
            val appName = if (packageName == "com.eg.android.AlipayGphone") "支付宝" else "微信"
            LogManager.addLog("=== $appName $timestamp ===", "事件: $eventTypeStr")
            LogManager.addLog("页面文本", texts.joinToString(" / "))
            
            // 纯诊断：单独打印包含收款/到账/金额关键词的候选文本
            if (packageName == "com.tencent.mm") {
                val keywords = listOf("收款", "到账", "元", "¥", "￥")
                texts.forEach { t ->
                    if (keywords.any { t.contains(it) }) {
                        LogManager.addLog("邮付无障碍候选文本", t)
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.addLog("无障碍异常", e.message ?: "未知错误")
        }
    }

    /**
     * 递归遍历节点收集文本
     */
    private fun traverseNode(node: AccessibilityNodeInfo?, texts: MutableList<String>, depth: Int) {
        if (node == null || depth > 10) return
        try {
            if (!node.text.isNullOrEmpty()) texts.add(node.text.toString())
            if (!node.contentDescription.isNullOrEmpty()) texts.add("[按钮]" + node.contentDescription.toString())
            for (i in 0 until node.childCount) {
                traverseNode(node.getChild(i), texts, depth + 1)
            }
        } catch (e: Exception) {
            // 忽略节点读取异常
        }
    }

    override fun onInterrupt() {
        LogManager.addLog("无障碍诊断", "服务中断")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogManager.addLog("✅ 无障碍诊断服务已启动", "现在打开支付宝/微信支付页面，日志会自动打印页面所有文本")
    }
}
