package com.example.damaidiag

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val damaiPackage = "cn.damai"
    private val prefs by lazy { getSharedPreferences("diagnostics", MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.SIMPLIFIED_CHINESE)
    private lateinit var networkValue: TextView
    private lateinit var serviceValue: TextView
    private lateinit var logsValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }
        val scroll = ScrollView(this)
        scroll.addView(page)

        page.addView(label("大麦 APP 页面诊断", 26f, true))
        page.addView(label("V0.2 · 只观察大麦 APP 可见提示，不自动点击或提交订单", 14f, false), top(4))

        page.addView(section("当前网络", "读取 Android 系统提供的网络状态"), top(16))
        networkValue = label("正在读取…", 15f, true)
        page.addView(networkValue, top(10))
        page.addView(button("刷新网络状态") { refreshNetwork() }, top(8))

        page.addView(section("大麦 APP 页面观察", "只监测 cn.damai 前台界面中的有限错误提示，不保存整页文字"), top(16))
        serviceValue = label("正在检查无障碍服务…", 14f, false)
        page.addView(serviceValue, top(10))
        page.addView(button("开启 / 检查页面观察权限") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, top(8))
        page.addView(button("打开大麦 APP") { openDamaiApp() }, top(8))

        page.addView(section("手动记录", "当大麦页面出现提示时，也可以手动记一条"), top(16))
        page.addView(button("记录当前失败提示") { recordFailure() }, top(8))

        page.addView(section("本地诊断记录", "只保存在本机，最多保留最近 80 条"), top(16))
        page.addView(button("复制诊断报告") { copyReport() }, top(8))
        page.addView(button("清空本地记录") { clearLogs() }, top(8))
        logsValue = label("暂无记录", 12f, false)
        page.addView(logsValue, top(8))

        page.addView(section("说明", "本版本不测试网页延迟。它通过你主动启用的 Android 无障碍服务观察大麦 APP（包名 cn.damai）页面上的少量已知提示词，记录提示类别、时间和网络类型；不会自动点击、刷新、下单、读取或保存整页文字，也不访问或重放购票接口。不同页面布局可能不暴露文字，未记录到提示不代表服务器正常。"), top(16))

        setContentView(scroll)
        refreshNetwork()
        refreshServiceStatus()
        renderLogs()
    }

    override fun onResume() {
        super.onResume()
        if (::networkValue.isInitialized) {
            refreshNetwork()
            refreshServiceStatus()
            renderLogs()
        }
    }

    private fun refreshNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
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

    private fun refreshServiceStatus() {
        val component = ComponentName(this, DamaiScreenMonitorService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any {
                it.equals(component.flattenToString(), ignoreCase = true) ||
                    it.equals(component.flattenToShortString(), ignoreCase = true)
            }
        serviceValue.text = if (enabled) {
            "页面观察：已开启\n打开大麦 APP 并正常操作；出现受支持的提示时，本工具会记录提示类别。"
        } else {
            "页面观察：尚未开启\n点击下方按钮进入系统设置，找到“大麦 APP 页面观察”并手动开启。"
        }
    }

    private fun openDamaiApp() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(damaiPackage)
            if (launch == null) {
                toast("没有找到包名为 cn.damai 的大麦 APP，请先安装官方大麦 APP")
                return
            }
            startActivity(launch)
        } catch (_: Exception) {
            toast("暂时无法打开大麦 APP")
        }
    }

    private fun recordFailure() {
        val options = arrayOf(
            "人多排队繁忙", "排队人数较多 / 请重试", "请求异常 / 操作频繁",
            "网络异常 / 页面加载失败", "缺票 / 暂无余票", "已售罄",
            "验证码 / 安全验证", "其他提示"
        )
        AlertDialog.Builder(this).setTitle("选择刚才看到的提示")
            .setItems(options) { _, index ->
                appendLog("[" + dateFormat.format(Date()) + "] 手动记录 | 网络=" + networkType() + " | 提示=" + options[index])
                renderLogs()
                toast("已记录")
            }.setNegativeButton("取消", null).show()
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

    private fun appendLog(line: String) {
        synchronized(prefs) {
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
        val report = "大麦 APP 页面诊断 V0.2\n当前网络：\n" + networkValue.text +
            "\n页面观察：\n" + serviceValue.text + "\n记录：\n" +
            prefs.getString("logs", "暂无记录").orEmpty() +
            "\n\n本工具只记录可见提示类别，不读取或保存整页内容，不自动点击或提交订单。"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("大麦页面诊断报告", report))
        toast("报告已复制")
    }

    private fun clearLogs() {
        AlertDialog.Builder(this).setTitle("清空本地记录？")
            .setMessage("只删除本工具保存的诊断记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                prefs.edit().remove("logs").apply()
                renderLogs()
                toast("已清空")
            }.show()
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
