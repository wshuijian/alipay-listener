package com.example.damaidiag

import android.accessibilityservice.AccessibilityService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads only selected error phrases from the Damai app's visible UI.
 * It never clicks controls, enters data, navigates, or sends requests.
 */
class DamaiScreenMonitorService : AccessibilityService() {
    private val targetPackage = "cn.damai"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE)
    private var lastCategory = ""
    private var lastAtElapsed = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentEvent = event ?: return
        if (currentEvent.packageName?.toString() != targetPackage) return
        val root = rootInActiveWindow ?: currentEvent.source ?: return
        val pieces = ArrayList<String>()
        collectText(root, pieces, intArrayOf(0), 0)
        if (pieces.isEmpty()) return

        val combined = pieces.joinToString(separator = "").lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
        val category = detectCategory(combined) ?: return
        val now = SystemClock.elapsedRealtime()
        if (category == lastCategory && now - lastAtElapsed < 15_000L) return
        lastCategory = category
        lastAtElapsed = now
        appendOnlyCategory(category)
    }

    override fun onInterrupt() {
        // No action needed; this service does not perform interactions.
    }

    private fun collectText(node: AccessibilityNodeInfo?, output: MutableList<String>, counter: IntArray, depth: Int) {
        if (node == null || depth > 24 || counter[0] >= 700) return
        counter[0] += 1
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { output.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { output.add(it) }
        for (index in 0 until node.childCount) {
            if (counter[0] >= 700) break
            collectText(node.getChild(index), output, counter, depth + 1)
        }
    }

    private fun detectCategory(text: String): String? = when {
        text.contains("人多排队繁忙") || text.contains("排队人数较多") ||
            (text.contains("当前排队人数") && text.contains("繁忙")) ||
            (text.contains("排队中") && text.contains("请重试")) -> "人多排队繁忙 / 排队提示"
        text.contains("请求异常") || text.contains("操作频繁") ||
            text.contains("请求过于频繁") || text.contains("访问异常") -> "请求异常 / 操作频繁"
        text.contains("暂无余票") || text.contains("缺票") || text.contains("无可售") -> "缺票 / 暂无余票"
        text.contains("已售罄") || text.contains("售罄") -> "已售罄"
        text.contains("网络异常") || text.contains("网络繁忙") ||
            text.contains("加载失败") || text.contains("连接失败") -> "网络异常 / 页面加载失败"
        text.contains("验证码") || text.contains("安全验证") || text.contains("风险验证") -> "验证码 / 安全验证"
        else -> null
    }

    private fun appendOnlyCategory(category: String) {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val network = when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他网络"
        }
        val line = "[" + dateFormat.format(Date()) + "] 自动识别 | 网络=" + network + " | 提示=" + category
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        synchronized(prefs) {
            val old = prefs.getString("logs", "").orEmpty()
            val all = (old.lines().filter { it.isNotBlank() } + line).takeLast(80)
            prefs.edit().putString("logs", all.joinToString("\n")).apply()
        }
    }
}
