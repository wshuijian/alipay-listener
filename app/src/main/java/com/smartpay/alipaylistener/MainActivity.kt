package com.smartpay.alipaylistener

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.Window
import android.widget.*
import android.speech.tts.TextToSpeech
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var homeListView: AbsListView
    private lateinit var historyListView: AbsListView
    private lateinit var homeAdapter: PaymentAdapter
    private lateinit var historyAdapter: PaymentAdapter
    private lateinit var tvStatusTop: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvShopName: TextView
    private lateinit var tvHomeDate: TextView
    private lateinit var tvDataTotal: TextView
    private lateinit var tvDataCount: TextView
    private lateinit var tvHistoryPeriod: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var panelHome: View
    private lateinit var panelData: View
    private lateinit var panelDevice: View
    private lateinit var panelSettings: View
    private lateinit var bottomBar: View
    private lateinit var topActions: View
    private lateinit var etPairCode: EditText
    private lateinit var etShopName: EditText
    private lateinit var tvLog: TextView
    private lateinit var cbTtsSwitch: CheckBox
    private lateinit var rgColumns: RadioGroup

    private val prefsName = "alipay_listener_prefs"
    private val paymentsKey = "recent_payments"
    private val handler = Handler(Looper.getMainLooper())
    private val allPayments = mutableListOf<Payment>()
    private val recentPayments = mutableListOf<Payment>()
    private val historyPayments = mutableListOf<Payment>()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateLabelFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
    private var displayColumns = 5
    private var historyRange = HistoryRange.TODAY
    private val customHistoryDate = Calendar.getInstance()
    private var tts: TextToSpeech? = null
    private var ttsEnabled = true

    private enum class HistoryRange { TODAY, YESTERDAY, LAST_7_DAYS, CUSTOM }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)
        bindViews()
        loadPayments()
        setupActions()
        showPanel(0)
        updateStatus()
        startClock()

        LogManager.setLogCallback { log -> runOnUiThread { tvLog.append(log + "\n") } }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
        MqttClientManager.setPaymentCallback { amount, channel ->
            runOnUiThread { addPayment(amount, channel) }
        }
        MqttClientManager.setStatusCallback { _, _ -> runOnUiThread { updateStatus() } }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun bindViews() {
        homeListView = findViewById(R.id.lv_payments)
        historyListView = findViewById(R.id.lv_history_payments)
        homeAdapter = PaymentAdapter(recentPayments, true, R.layout.item_payment)
        historyAdapter = PaymentAdapter(historyPayments, false, R.layout.item_payment_history)
        homeListView.adapter = homeAdapter
        historyListView.adapter = historyAdapter

        tvStatusTop = findViewById(R.id.tv_status_top)
        tvClock = findViewById(R.id.tv_clock)
        tvShopName = findViewById(R.id.tv_shop_name)
        tvHomeDate = findViewById(R.id.tv_home_date)
        tvDataTotal = findViewById(R.id.tv_data_total)
        tvDataCount = findViewById(R.id.tv_data_count)
        tvHistoryPeriod = findViewById(R.id.tv_history_period)
        tvHistoryEmpty = findViewById(R.id.tv_history_empty)
        panelHome = findViewById(R.id.panel_home)
        panelData = findViewById(R.id.panel_data)
        panelDevice = findViewById(R.id.panel_device)
        panelSettings = findViewById(R.id.panel_settings)
        bottomBar = findViewById(R.id.bottom_bar)
        topActions = findViewById(R.id.top_actions)
        etPairCode = findViewById(R.id.et_pair_code)
        etShopName = findViewById(R.id.et_shop_name)
        tvLog = findViewById(R.id.tv_log)
        cbTtsSwitch = findViewById(R.id.cb_tts_switch)
        rgColumns = findViewById(R.id.rg_columns)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        displayColumns = prefs.getInt("display_columns", 5).coerceIn(3, 6)
        ttsEnabled = prefs.getBoolean("tts_enabled", true)
        tvShopName.text = prefs.getString("shop_name", "长风照相馆") ?: "长风照相馆"
        etShopName.setText(tvShopName.text)
        etPairCode.setText(prefs.getString("pair_code", ""))
        cbTtsSwitch.isChecked = ttsEnabled
        rgColumns.check(columnRadioId(displayColumns))
        applyDisplayDensity()
        updateHomeDate()
    }

    private fun setupActions() {
        findViewById<ImageButton>(R.id.btn_fullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<ImageButton>(R.id.btn_orientation).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        findViewById<ImageButton>(R.id.btn_refresh_status).setOnClickListener {
            updateStatus()
            Toast.makeText(this, "状态已刷新", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_refresh_device).setOnClickListener {
            updateStatus()
            Toast.makeText(this, "设备状态已刷新", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            val code = etPairCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "请输入6位配对码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("pair_code", code).apply()
            val intent = Intent(this, KeepAliveService::class.java).putExtra("pair_code", code)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "监听服务启动请求已发送", Toast.LENGTH_SHORT).show()
            updateStatus()
        }
        findViewById<Button>(R.id.btn_open_notification_access).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        findViewById<Button>(R.id.btn_save_shop_name).setOnClickListener {
            val name = etShopName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "店铺名称不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("shop_name", name).apply()
            tvShopName.text = name
            Toast.makeText(this, "店铺名称已保存", Toast.LENGTH_SHORT).show()
        }

        cbTtsSwitch.setOnCheckedChangeListener { _, checked ->
            ttsEnabled = checked
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putBoolean("tts_enabled", checked).apply()
        }
        rgColumns.setOnCheckedChangeListener { _, checkedId ->
            val columns = when (checkedId) {
                R.id.rb_cols_3 -> 3
                R.id.rb_cols_4 -> 4
                R.id.rb_cols_5 -> 5
                R.id.rb_cols_6 -> 6
                else -> 5
            }
            if (columns != displayColumns) {
                displayColumns = columns
                getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putInt("display_columns", columns).apply()
                applyDisplayDensity()
            }
        }

        findViewById<TextView>(R.id.tab_home).setOnClickListener { showPanel(0) }
        findViewById<TextView>(R.id.tab_data).setOnClickListener { showPanel(1) }
        findViewById<TextView>(R.id.tab_device).setOnClickListener { showPanel(2) }
        findViewById<TextView>(R.id.tab_settings).setOnClickListener { showPanel(3) }

        findViewById<TextView>(R.id.btn_range_today).setOnClickListener { setHistoryRange(HistoryRange.TODAY) }
        findViewById<TextView>(R.id.btn_range_yesterday).setOnClickListener { setHistoryRange(HistoryRange.YESTERDAY) }
        findViewById<TextView>(R.id.btn_range_7days).setOnClickListener { setHistoryRange(HistoryRange.LAST_7_DAYS) }
        findViewById<TextView>(R.id.btn_range_custom).setOnClickListener { showCustomDatePicker() }

        findViewById<Button>(R.id.btn_copy_log).setOnClickListener {
            val allLogs = LogManager.getAllLogs().joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("收款日志", allLogs))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPanel(index: Int) {
        panelHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelData.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelDevice.visibility = if (index == 2) View.VISIBLE else View.GONE
        panelSettings.visibility = if (index == 3) View.VISIBLE else View.GONE

        val tabs = listOf(
            findViewById<TextView>(R.id.tab_home),
            findViewById<TextView>(R.id.tab_data),
            findViewById<TextView>(R.id.tab_device),
            findViewById<TextView>(R.id.tab_settings)
        )
        tabs.forEachIndexed { i, tab ->
            tab.setTextColor(if (i == index) 0xFF00C98D.toInt() else 0xFF8392A3.toInt())
            tab.alpha = if (i == index) 1f else 0.82f
        }
        if (index == 1) refreshHistoryList()
        updateStatus()
    }

    private fun applyDisplayDensity() {
        val grid = homeListView as? GridView
        if (grid != null) grid.numColumns = displayColumns
        homeAdapter.notifyDataSetChanged()
        historyAdapter.notifyDataSetChanged()
    }

    private fun columnRadioId(columns: Int): Int = when (columns) {
        3 -> R.id.rb_cols_3
        4 -> R.id.rb_cols_4
        6 -> R.id.rb_cols_6
        else -> R.id.rb_cols_5
    }

    private fun addPayment(amount: String, channel: String) {
        if (amount.trim().isEmpty()) return
        val finalChannel = when {
            channel.isEmpty() -> "银行卡收款"
            channel.contains("微信") -> "微信支付"
            channel.contains("支付宝") -> "支付宝"
            else -> channel
        }
        allPayments.add(0, Payment(amount.trim(), finalChannel, System.currentTimeMillis()))
        savePayments()
        refreshHomeList()
        refreshHistoryList()
        if (ttsEnabled) {
            tts?.speak(finalChannel + "到账" + amount + "元", TextToSpeech.QUEUE_FLUSH, null, "payment_" + System.currentTimeMillis())
        }
    }

    private fun refreshHomeList() {
        recentPayments.clear()
        recentPayments.addAll(allPayments.sortedByDescending { it.time }.take(100))
        updateHomeDate()
        homeAdapter.notifyDataSetChanged()
    }

    private fun updateHomeDate() {
        tvHomeDate.text = "今天 · " + dateLabelFormat.format(Date())
    }

    private fun setHistoryRange(range: HistoryRange) {
        historyRange = range
        if (range == HistoryRange.CUSTOM) {
            showCustomDatePicker()
            return
        }
        refreshHistoryList()
    }

    private fun showCustomDatePicker() {
        val initial = if (historyRange == HistoryRange.CUSTOM) customHistoryDate else Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                customHistoryDate.set(year, month, day, 0, 0, 0)
                customHistoryDate.set(Calendar.MILLISECOND, 0)
                historyRange = HistoryRange.CUSTOM
                refreshHistoryList()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun dayStart(source: Calendar): Calendar {
        return (source.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun historyBounds(): Pair<Long, Long> {
        val todayStart = dayStart(Calendar.getInstance())
        val start = when (historyRange) {
            HistoryRange.TODAY -> todayStart
            HistoryRange.YESTERDAY -> (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            HistoryRange.LAST_7_DAYS -> (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }
            HistoryRange.CUSTOM -> dayStart(customHistoryDate)
        }
        val endExclusive = when (historyRange) {
            HistoryRange.LAST_7_DAYS -> (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            else -> (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        }
        return Pair(start.timeInMillis, endExclusive.timeInMillis)
    }

    private fun refreshHistoryList() {
        val bounds = historyBounds()
        historyPayments.clear()
        historyPayments.addAll(
            allPayments.filter { it.time >= bounds.first && it.time < bounds.second }.sortedByDescending { it.time }
        )

        tvHistoryPeriod.text = when (historyRange) {
            HistoryRange.TODAY -> "今天 · " + dateLabelFormat.format(Date())
            HistoryRange.YESTERDAY -> "昨天 · " + dateLabelFormat.format(Date(dayStart(Calendar.getInstance()).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis))
            HistoryRange.LAST_7_DAYS -> {
                val startDate = Date(bounds.first)
                val endDate = Date(bounds.second - 1)
                "近7天 · " + shortDateFormat.format(startDate) + "—" + shortDateFormat.format(endDate)
            }
            HistoryRange.CUSTOM -> dateLabelFormat.format(customHistoryDate.time)
        }

        tvHistoryEmpty.visibility = if (historyPayments.isEmpty()) View.VISIBLE else View.GONE
        val total = historyPayments.fold(BigDecimal.ZERO) { sum, payment ->
            sum.add(payment.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }.setScale(2, RoundingMode.HALF_UP)
        tvDataTotal.text = "¥" + total.toPlainString()
        tvDataCount.text = historyPayments.size.toString() + " 笔"
        historyAdapter.notifyDataSetChanged()
        updateRangeButtonStyles()
    }

    private fun updateRangeButtonStyles() {
        val entries = listOf(
            Pair(R.id.btn_range_today, HistoryRange.TODAY),
            Pair(R.id.btn_range_yesterday, HistoryRange.YESTERDAY),
            Pair(R.id.btn_range_7days, HistoryRange.LAST_7_DAYS),
            Pair(R.id.btn_range_custom, HistoryRange.CUSTOM)
        )
        entries.forEach { (id, range) ->
            val view = findViewById<TextView>(id)
            val selected = historyRange == range
            view.setBackgroundResource(if (selected) R.drawable.bg_filter_active else R.drawable.bg_filter_idle)
            view.setTextColor(if (selected) 0xFF062019.toInt() else 0xFFD7E1EA.toInt())
        }
    }

    private fun savePayments() {
        val value = allPayments.joinToString(";") { it.time.toString() + "|" + it.amount + "|" + it.channel }
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(paymentsKey, value).apply()
    }

    private fun loadPayments() {
        val value = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(paymentsKey, "") ?: ""
        if (value.isNotEmpty()) {
            value.split(";").forEach {
                val p = it.split("|")
                if (p.size == 3) {
                    allPayments.add(Payment(p[1], p[2], p[0].toLongOrNull() ?: System.currentTimeMillis()))
                }
            }
        }
        allPayments.sortByDescending { it.time }
        refreshHomeList()
        refreshHistoryList()
    }

    private fun updateStatus() {
        val connected = MqttClientManager.isConnected()
        val paired = connected && MqttClientManager.isPaired()
        tvStatusTop.text = if (paired) "● 已连接" else if (connected) "● 未配对" else "● 未连接"
        tvStatusTop.setTextColor(if (paired) 0xFF00C98D.toInt() else 0xFFF2B84B.toInt())
        findViewById<TextView>(R.id.tv_device_status).text =
            if (connected) "MQTT：已连接" else "MQTT：未连接"
        findViewById<TextView>(R.id.tv_pair_status).text =
            if (paired) "设备配对：已配对" else "设备配对：等待配对"
        findViewById<TextView>(R.id.tv_notification_status).text =
            if (isNotificationServiceEnabled()) "通知监听权限：已开启" else "通知监听权限：未开启"
        findViewById<TextView>(R.id.tv_service_status).text =
            if (paired && isNotificationServiceEnabled()) "设备已具备基本收款监听条件" else "请检查连接、配对码和通知监听权限"
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (TextUtils.isEmpty(flat)) return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun startClock() {
        handler.post(object : Runnable {
            override fun run() {
                tvClock.text = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun toggleFullscreen() {
        val decor = window.decorView
        val flags = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val full = (decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
        decor.systemUiVisibility = if (full) View.SYSTEM_UI_FLAG_LAYOUT_STABLE else flags
        bottomBar.visibility = if (full) View.VISIBLE else View.GONE
        topActions.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE &&
            (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) {
            toggleFullscreen()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    data class Payment(val amount: String, val channel: String, val time: Long)

    private inner class PaymentAdapter(
        private val items: List<Payment>,
        private val highlightFirstItem: Boolean,
        private val layoutRes: Int
    ) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].time

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val view = convertView ?: android.view.LayoutInflater.from(this@MainActivity).inflate(layoutRes, parent, false)
            val item = items[position]
            val amountView = view.findViewById<TextView>(R.id.tv_payment_amount)
            val channelView = view.findViewById<TextView>(R.id.tv_payment_channel)
            val timeView = view.findViewById<TextView>(R.id.tv_payment_time)
            val iconView = view.findViewById<TextView>(R.id.tv_payment_channel_icon)
            val badgeView = view.findViewById<TextView>(R.id.tv_payment_latest_badge)

            amountView.text = "¥" + item.amount
            channelView.text = item.channel
            timeView.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.time))

            val channelColor = when {
                item.channel.contains("微信") -> 0xFF07C160.toInt()
                item.channel.contains("支付宝") -> 0xFF168BFF.toInt()
                else -> 0xFFF2B84B.toInt()
            }
            channelView.setTextColor(channelColor)
            amountView.setTextColor(if (highlightFirstItem && position == 0) channelColor else 0xFFF5F7FA.toInt())

            val iconText = when {
                item.channel.contains("微信") -> "微"
                item.channel.contains("支付宝") -> "支"
                else -> "银"
            }
            iconView.text = iconText
            val iconBackground = GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(channelColor)
            }
            iconView.background = iconBackground
            iconView.setTextColor(0xFFFFFFFF.toInt())

            val densityColumns = displayColumns
            val amountSize = when (densityColumns) {
                3 -> 30f
                4 -> 27f
                5 -> 24f
                else -> 21f
            }
            val isLatest = highlightFirstItem && position == 0
            amountView.textSize = amountSize + if (isLatest) 4f else 0f
            val channelSize = when (densityColumns) {
                3 -> 14f
                4 -> 13f
                5 -> 12f
                else -> 11f
            }
            val portrait = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            channelView.textSize = if (portrait) maxOf(12f, channelSize) else channelSize
            timeView.textSize = if (portrait) maxOf(11f, channelSize) else maxOf(10f, channelSize - 1f)
            if (badgeView != null) badgeView.visibility = if (isLatest) View.VISIBLE else View.GONE
            if (isLatest) view.setBackgroundResource(R.drawable.bg_payment_card_latest)
            else view.setBackgroundResource(R.drawable.bg_payment_card)
            return view
        }
    }
}
