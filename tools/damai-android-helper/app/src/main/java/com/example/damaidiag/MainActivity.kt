package com.example.damaidiag

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val eventUrl = "https://detail.damai.cn/item.htm?id=1090372637025"
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("diagnostics", MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE)
    private var lastProbeAt = 0L
    private var probeRunning = false

    private lateinit var networkValue: TextView
    private lateinit var probeValue: TextView
    private lateinit var logsValue: TextView
    private lateinit var probeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }
        val scroll = ScrollView(this)
        scroll.addView(page)

        page.addView(label("大麦网络助手", 26f, true))
        page.addView(label("南京站 · 网络诊断 V0.1", 14f, false), top(4))

        page.addView(section("当前网络", "读取 Android 系统网络状态"))
        networkValue = label("正在读取…", 15f, true)
        page.addView(networkValue, top(10))
        page.addView(button("刷新网络状态") { refreshNetwork() }, top(8))

        page.addView(section("大麦公开详情页", "单次手动请求，至少间隔 10 秒"), top(16))
        probeValue = label("尚未测试。不会读取登录信息或购票数据。", 14f, false)
        page.addView(probeValue, top(10))
        probeButton = button("测试大麦详情页") { probeDamai() }
        page.addView(probeButton, top(8))
        page.addView(button("打开官方演出页面") { openDamai() }, top(8))

        page.addView(section("排队失败记录", "只记录你手动选择的提示，不操作购票流程"), top(16))
        page.addView(button("记录一次失败提示") { recordFailure() }, top(8))
        page.addView(button("复制诊断报告") { copyReport() }, top(8))
        page.addView(button("清空本地记录") { clearLogs() }, top(8))
        logsValue = label("暂无记录", 12f, false)
        page.addView(logsValue, top(8))

        page.addView(section("说明", "本工具只检测手机网络和大麦公开详情页。详情页延迟不代表排队接口延迟，不能预测剩余票量或购票成功率；不会自动刷新、提交订单、重放请求或绕过排队。"), top(16))
        setContentView(scroll)
        refreshNetwork()
        renderLogs()
    }

    override fun onResume() {
        super.onResume()
        if (::networkValue.isInitialized) refreshNetwork()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active == null) null else cm.getNetworkCapabilities(active)
        val type = when {
            caps == null -> "无可用网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他网络"
        }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        networkValue.text = type + "\n系统检测到 Internet 能力：" + yesNo(internet) +
            "\n系统验证互联网可达：" + yesNo(validated)
    }

    private fun yesNo(value: Boolean) = if (value) "是" else "否"

    private fun probeDamai() {
        if (probeRunning) {
            toast("测试正在进行")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val wait = 10000L - (now - lastProbeAt)
        if (lastProbeAt > 0L && wait > 0L) {
            toast("请在 " + ((wait + 999L) / 1000L) + " 秒后再测")
            return
        }
        lastProbeAt = now
        probeRunning = true
        probeButton.isEnabled = false
        probeValue.text = "正在连接 detail.damai.cn…"
        val netLabel = networkType()
        executor.execute {
            val start = SystemClock.elapsedRealtime()
            var code: Int? = null
            var bytes = 0
            var failure: String? = null
            try {
                val connection = URL(eventUrl).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 6500
                    connection.readTimeout = 6500
                    connection.instanceFollowRedirects = true
                    connection.useCaches = false
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    code = connection.responseCode
                    val stream = if ((code ?: 0) >= 400) connection.errorStream else connection.inputStream
                    if (stream != null) stream.use { bytes = if (it.read() >= 0) 1 else 0 }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                failure = when (e) {
                    is java.net.SocketTimeoutException -> "连接或响应超时"
                    is java.net.UnknownHostException -> "DNS 域名解析失败"
                    is javax.net.ssl.SSLException -> "TLS/证书连接失败"
                    else -> e.javaClass.simpleName + ": " + (e.message ?: "连接失败")
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            val time = dateFormat.format(Date())
            val line = "[" + time + "] 详情页测试 | 网络=" + netLabel +
                " | 总耗时=" + elapsed + "ms | " +
                (if (code != null) "HTTP=" + code + " | 读取数据=" + bytes + "字节" else "失败=" + failure)
            appendLog(line)
            runOnUiThread {
                probeRunning = false
                probeButton.isEnabled = true
                probeValue.text = "网络：" + netLabel + "\n耗时：" + elapsed + " ms\n" +
                    (if (code != null) "HTTP 状态：" + code + "\n读取数据：" + bytes + " 字节"
                    else "结果：" + failure) +
                    "\n\n注意：结果仅代表公开详情页，不代表内部排队服务。"
                renderLogs()
            }
        }
    }

    private fun networkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> "无网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他网络"
        }
    }

    private fun openDamai() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(eventUrl)))
        } catch (_: Exception) {
            toast("未找到可打开链接的应用")
        }
    }

    private fun recordFailure() {
        val options = arrayOf("人多排队繁忙", "请求异常 / 操作频繁", "页面显示缺票 / 已售罄", "一直转圈 / 页面加载失败", "其他提示")
        AlertDialog.Builder(this)
            .setTitle("选择实际出现的提示")
            .setItems(options) { _, index ->
                appendLog("[" + dateFormat.format(Date()) + "] 手动记录 | 网络=" + networkType() + " | 提示=" + options[index])
                renderLogs()
                toast("已记录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun appendLog(line: String) {
        synchronized(this) {
            val old = prefs.getString("logs", "").orEmpty()
            val all = (old.lines().filter { it.isNotBlank() } + line).takeLast(80)
            prefs.edit().putString("logs", all.joinToString("\n")).apply()
        }
    }

    private fun renderLogs() {
        val all = prefs.getString("logs", "").orEmpty()
        logsValue.text = if (all.isBlank()) "暂无记录" else all.lines().takeLast(12).asReversed().joinToString("\n\n")
    }

    private fun copyReport() {
        val report = "大麦网络助手 V0.1\n当前网络：" + networkValue.text + "\n记录：\n" +
            prefs.getString("logs", "暂无记录") +
            "\n\n说明：只检测本地网络和公开详情页，不表示内部排队服务状态。"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("大麦网络诊断报告", report))
        toast("报告已复制")
    }

    private fun clearLogs() {
        AlertDialog.Builder(this)
            .setTitle("清空本地记录？")
            .setMessage("只删除本工具保存的诊断记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                prefs.edit().remove("logs").apply()
                renderLogs()
                toast("已清空")
            }
            .show()
    }

    private fun section(title: String, subtitle: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(1).toFloat()
        }
        box.addView(label(title, 17f, true))
        box.addView(label(subtitle, 12f, false), top(3))
        return box
    }

    private fun label(value: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(if (bold) 0xFF18202A.toInt() else 0xFF475467.toInt())
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }

    private fun button(title: String, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            minHeight = dp(44)
            setOnClickListener { action() }
        }

    private fun top(amount: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(amount)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
