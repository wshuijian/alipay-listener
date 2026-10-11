package com.smartpay.alipaylistener

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AnimationUtils
import android.widget.*
import android.speech.tts.TextToSpeech
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {
    private var homeListView: ListView? = null
    private var homeGridView: GridView? = null
    private var historyListView: ListView? = null
    private var historyGridView: GridView? = null
    private lateinit var homeAdapter: PaymentAdapter
    private lateinit var historyAdapter: PaymentAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvClock: TextView
    private lateinit var panelHome: View
    private lateinit var panelData: View
    private lateinit var panelDevice: View
    private lateinit var panelSettings: View
    private lateinit var panelHistory: View
    private lateinit var bottomBar: View
    private lateinit var topActions: View
    private lateinit var etPairCode: EditText
    private lateinit var tvLog: TextView
    private lateinit var tvDataTotal: TextView
    private lateinit var tvDataCount: TextView
    private lateinit var tvHistoryDate: TextView
    private lateinit var tvHistoryTotal: TextView
    private lateinit var tvHistoryCount: TextView
    private lateinit var spDisplayColumns: Spinner
    private lateinit var cbTtsSwitch: CheckBox

    private val prefsName = "alipay_listener_prefs"
    private val paymentsKey = "recent_payments"
    private val displayColumnsKey = "display_columns"
    private val handler = Handler(Looper.getMainLooper())
    private val allPayments = mutableListOf<Payment>()
    private val todayPayments = mutableListOf<Payment>()
    private val historyPayments = mutableListOf<Payment>()
    private val selectedHistoryDay = Calendar.getInstance()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateLabelFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
    private var tts: TextToSpeech? = null
    private var ttsEnabled = true
    private var displayColumns = 8
    private var effectiveDisplayColumns = 8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)
        bindViews()
        loadPayments()
        setupActions()
        showPanel(0)
        updateStatus()
        updateTodaySummary()
        startClock()
        LogManager.setLogCallback { log -> runOnUiThread { tvLog.append(log + "\n") } }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.CHINESE
        }
        MqttClientManager.setPaymentCallback { amount, channel ->
            runOnUiThread { addPayment(amount, channel) }
        }
        MqttClientManager.setStatusCallback { _, _ ->
            runOnUiThread { updateStatus() }
        }
    }

    private fun bindViews() {
        panelHome = findViewById(R.id.panel_home)
        panelData = findViewById(R.id.panel_data)
        panelDevice = findViewById(R.id.panel_device)
        panelSettings = findViewById(R.id.panel_settings)
        panelHistory = findViewById(R.id.panel_history)
        bottomBar = findViewById(R.id.bottom_bar)
        topActions = findViewById(R.id.top_actions)
        tvStatus = findViewById(R.id.tv_status_top)
        tvClock = findViewById(R.id.tv_clock)
        etPairCode = findViewById(R.id.et_pair_code)
        tvLog = findViewById(R.id.tv_log)
        tvDataTotal = findViewById(R.id.tv_data_total)
        tvDataCount = findViewById(R.id.tv_data_count)
        tvHistoryDate = findViewById(R.id.tv_history_date)
        tvHistoryTotal = findViewById(R.id.tv_history_total)
        tvHistoryCount = findViewById(R.id.tv_history_count)
        spDisplayColumns = findViewById(R.id.sp_display_columns)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        displayColumns = prefs.getInt(displayColumnsKey, 8).coerceIn(4, 12)
        ttsEnabled = prefs.getBoolean("tts_enabled", true)
        findViewById<TextView>(R.id.tv_shop_name).text = prefs.getString("shop_name", "长风照相馆") ?: "长风照相馆"

        homeAdapter = PaymentAdapter(this, todayPayments, true)
        historyAdapter = PaymentAdapter(this, historyPayments, false)
        when (val homeView = findViewById<View>(R.id.lv_payments)) {
            is ListView -> { homeListView = homeView; homeView.adapter = homeAdapter }
            is GridView -> { homeGridView = homeView; homeView.adapter = homeAdapter }
            else -> throw IllegalStateException("首页流水控件不存在或类型不受支持")
        }
        when (val historyView = findViewById<View>(R.id.lv_history_payments)) {
            is ListView -> { historyListView = historyView; historyView.adapter = historyAdapter }
            is GridView -> { historyGridView = historyView; historyView.adapter = historyAdapter }
            else -> throw IllegalStateException("历史流水控件不存在或类型不受支持")
        }
        findViewById<View>(R.id.tv_home_empty).also {
            if (homeListView != null) homeListView?.emptyView = it else homeGridView?.emptyView = it
        }
        findViewById<View>(R.id.tv_history_empty).also {
            if (historyListView != null) historyListView?.emptyView = it else historyGridView?.emptyView = it
        }

        cbTtsSwitch = findViewById(R.id.cb_tts_switch)
        cbTtsSwitch.isChecked = ttsEnabled
        etPairCode.setText(prefs.getString("pair_code", ""))
        val columnOptions = (4..12).map { "$it 列" }
        spDisplayColumns.adapter = ArrayAdapter(this, R.layout.item_spinner_column, columnOptions).apply {
            setDropDownViewResource(R.layout.item_spinner_column)
        }
        spDisplayColumns.setSelection(displayColumns - 4, false)
        applyDisplayDensity()
    }

    private fun setupActions() {
        findViewById<TextView>(R.id.tab_home).setOnClickListener { showPanel(0) }
        findViewById<TextView>(R.id.tab_data).setOnClickListener { showPanel(1) }
        findViewById<TextView>(R.id.tab_device).setOnClickListener { showPanel(2) }
        findViewById<TextView>(R.id.tab_settings).setOnClickListener { showPanel(3) }
        findViewById<ImageButton>(R.id.btn_fullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<ImageButton>(R.id.btn_orientation).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        findViewById<ImageButton>(R.id.btn_refresh).setOnClickListener {
            updateStatus()
            Toast.makeText(this, "状态已刷新", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_open_history).setOnClickListener {
            selectedHistoryDay.timeInMillis = System.currentTimeMillis()
            refreshHistoryList()
            showPanel(4)
        }
        findViewById<View>(R.id.btn_history_back).setOnClickListener { showPanel(1) }
        findViewById<View>(R.id.btn_history_prev).setOnClickListener {
            selectedHistoryDay.add(Calendar.DAY_OF_MONTH, -1)
            refreshHistoryList()
        }
        findViewById<View>(R.id.btn_history_next).setOnClickListener {
            if (dayFormat.format(selectedHistoryDay.time) < dayFormat.format(Calendar.getInstance().time)) {
                selectedHistoryDay.add(Calendar.DAY_OF_MONTH, 1)
                refreshHistoryList()
            }
        }
        findViewById<View>(R.id.btn_pick_history_date).setOnClickListener {
            val today = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    selectedHistoryDay.set(year, month, day, 12, 0, 0)
                    selectedHistoryDay.set(Calendar.MILLISECOND, 0)
                    refreshHistoryList()
                },
                selectedHistoryDay.get(Calendar.YEAR),
                selectedHistoryDay.get(Calendar.MONTH),
                selectedHistoryDay.get(Calendar.DAY_OF_MONTH)
            ).apply {
                datePicker.maxDate = today.timeInMillis
                show()
            }
        }
        findViewById<Button>(R.id.btn_device_notification_access).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        findViewById<Button>(R.id.btn_open_notification_access).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
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
            Toast.makeText(this, "监听服务已启动", Toast.LENGTH_SHORT).show()
            updateStatus()
        }
        cbTtsSwitch.setOnCheckedChangeListener { _, checked ->
            ttsEnabled = checked
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putBoolean("tts_enabled", checked).apply()
        }
        spDisplayColumns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedColumns = (position + 4).coerceIn(4, 12)
                if (selectedColumns != displayColumns) {
                    displayColumns = selectedColumns
                    getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                        .putInt(displayColumnsKey, displayColumns).apply()
                    applyDisplayDensity()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
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
        panelHistory.visibility = if (index == 4) View.VISIBLE else View.GONE
        val activeTab = if (index == 4) R.id.tab_data else when (index) {
            0 -> R.id.tab_home
            1 -> R.id.tab_data
            2 -> R.id.tab_device
            else -> R.id.tab_settings
        }
        listOf(R.id.tab_home, R.id.tab_data, R.id.tab_device, R.id.tab_settings).forEach { id ->
            findViewById<TextView>(id).setTextColor(if (id == activeTab) 0xFF00D6A0.toInt() else 0xFF8B9CAF.toInt())
        }
    }

    private fun addPayment(amount: String, channel: String) {
        if (amount.trim().isEmpty()) return
        val normalizedChannel = channel.trim()
        val lowerChannel = normalizedChannel.lowercase(Locale.ROOT)
        val genericBankLabels = setOf(
            "", "收款", "银行卡", "银行卡收款", "bank", "bank_mqtt",
            "unknown", "unknown_channel", "未知渠道", "generic_bank"
        )
        val finalChannel = when {
            lowerChannel in genericBankLabels -> "银行卡收款"
            normalizedChannel.contains("微信") -> "微信支付"
            normalizedChannel.contains("支付宝") -> "支付宝"
            else -> normalizedChannel
        }
        // MQTT 回调仅提供金额和渠道，不按金额猜测重复交易，避免吞掉同额真实收款。
        allPayments.add(0, Payment(amount.trim(), finalChannel, System.currentTimeMillis()))
        savePayments()
        refreshHomeList()
        refreshHistoryList()
        updateTodaySummary()
        if (ttsEnabled) {
            val speechText = when (finalChannel) {
                "微信支付", "支付宝" -> "${finalChannel}到账${amount}元"
                else -> {
                    val spokenChannel = if (finalChannel.endsWith("收款")) finalChannel else "${finalChannel}收款"
                    "${spokenChannel}到账${amount}元"
                }
            }
            tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "payment_${System.currentTimeMillis()}")
        }
    }

    private fun refreshHomeList() {
        todayPayments.clear()
        val todayStr = dayFormat.format(Calendar.getInstance().time)
        allPayments.filter { dayFormat.format(Date(it.time)) == todayStr }
            .sortedByDescending { it.time }
            .forEach { todayPayments.add(it) }
        homeAdapter.notifyDataSetChanged()
        applyDisplayDensity()
    }

    private fun refreshHistoryList() {
        historyPayments.clear()
        val dayStr = dayFormat.format(selectedHistoryDay.time)
        allPayments.filter { dayFormat.format(Date(it.time)) == dayStr }
            .sortedByDescending { it.time }
            .forEach { historyPayments.add(it) }
        tvHistoryDate.text = dateLabelFormat.format(selectedHistoryDay.time)
        // 历史日期统计：复用当前筛选结果，展示总金额与笔数
        val total = historyPayments.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        tvHistoryTotal.text = "总金额：¥" + DecimalFormat("#,##0.00").format(total)
        tvHistoryCount.text = "${historyPayments.size}笔"
        val isToday = dayStr == dayFormat.format(Calendar.getInstance().time)
        findViewById<View>(R.id.btn_history_next).apply {
            isEnabled = !isToday
            alpha = if (isToday) 0.35f else 1.0f
        }
        historyAdapter.notifyDataSetChanged()
        applyDisplayDensity()
    }

    private fun updateTodaySummary() {
        val todayStr = dayFormat.format(Calendar.getInstance().time)
        val todays = allPayments.filter { dayFormat.format(Date(it.time)) == todayStr }
        val total = todays.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        val money = "¥" + DecimalFormat("#,##0.00").format(total)
        tvDataTotal.text = money
        tvDataCount.text = "${todays.size} 笔交易"
    }

    private fun applyDisplayDensity() {
        // 根据设备可用宽度限制实际列数，避免手机横屏卡片过窄。
        val availableWidthDp = (resources.configuration.screenWidthDp - 24).coerceAtLeast(320)
        val maxColumnsThatFit = (availableWidthDp / 80).coerceIn(4, 12)
        effectiveDisplayColumns = minOf(displayColumns, maxColumnsThatFit)
        homeGridView?.numColumns = effectiveDisplayColumns
        historyGridView?.numColumns = effectiveDisplayColumns
        homeGridView?.horizontalSpacing = dp(10)
        homeGridView?.verticalSpacing = dp(10)
        historyGridView?.horizontalSpacing = dp(10)
        historyGridView?.verticalSpacing = dp(10)
        if (::homeAdapter.isInitialized) homeAdapter.notifyDataSetChanged()
        if (::historyAdapter.isInitialized) historyAdapter.notifyDataSetChanged()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun loadPayments() {
        val value = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(paymentsKey, "") ?: return
        if (value.isNotEmpty()) {
            value.split(";").forEach { row ->
                val p = row.split("|")
                if (p.size == 3) allPayments.add(Payment(p[1], p[2], p[0].toLongOrNull() ?: System.currentTimeMillis()))
            }
        }
        refreshHomeList()
        refreshHistoryList()
        updateTodaySummary()
    }

    private fun savePayments() {
        val value = allPayments.joinToString(";") { "${it.time}|${it.amount}|${it.channel}" }
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(paymentsKey, value).apply()
    }

    private fun updateStatus() {
        val paired = MqttClientManager.isConnected() && MqttClientManager.isPaired()
        tvStatus.text = if (paired) "已连接" else "未连接"
        tvStatus.setTextColor(if (paired) 0xFF62D88F.toInt() else 0xFFF2B84B.toInt())
        findViewById<TextView>(R.id.tv_device_status).text = if (paired) "MQTT：已连接并已配对" else "MQTT：等待连接或配对"
        findViewById<TextView>(R.id.tv_notification_status).text =
            if (isNotificationServiceEnabled()) "通知监听：已开启" else "通知监听：未开启"
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
        private val context: Context,
        private val items: List<Payment>,
        private val highlightFirst: Boolean
    ) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].time

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.item_payment, parent, false)
            val item = items[position]
            val amountTv = view.findViewById<TextView>(R.id.tv_payment_amount)
            val channelTv = view.findViewById<TextView>(R.id.tv_payment_channel)
            val timeTv = view.findViewById<TextView>(R.id.tv_payment_time)
            val latestBadge = view.findViewById<TextView>(R.id.tv_payment_latest_badge)
            amountTv.text = "¥" + item.amount
            channelTv.text = item.channel
            timeTv.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.time))
            val channelColor = when {
                item.channel.contains("微信") -> 0xFF07C160.toInt()
                item.channel.contains("支付宝") -> 0xFF1677FF.toInt()
                else -> 0xFFF2B84B.toInt()
            }
            channelTv.setTextColor(channelColor)
            amountTv.setTextColor(channelColor)
            val latest = highlightFirst && position == 0
            if (latest) {
                // 最新订单背景颜色绑定渠道：微信绿、支付宝蓝、银行/第三方橙黄
                val latestStrokeColor = when {
                    item.channel.contains("微信") -> 0xFF07C160.toInt()
                    item.channel.contains("支付宝") -> 0xFF1677FF.toInt()
                    else -> 0xFFF2B84B.toInt()
                }
                val latestBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0xFF1B2A3A.toInt())
                    setStroke(dp(1), latestStrokeColor)
                }
                view.setBackground(latestBg)
                latestBadge.visibility = View.VISIBLE
                // 最新订单高亮动画：透明度呼吸一次（0.5→1.0→0.5→1.0），仅首页第一笔播放
                view.startAnimation(AnimationUtils.loadAnimation(context, R.anim.latest_payment_highlight))
            } else {
                view.setBackgroundResource(R.drawable.bg_payment_card)
                latestBadge.visibility = View.GONE
            }
            val isGrid = parent is GridView
            val availableWidthDp = (resources.configuration.screenWidthDp - 24).coerceAtLeast(320)
            val cellWidthDp = if (isGrid) availableWidthDp / effectiveDisplayColumns else 0
            val heightDp = if (isGrid) {
                when {
                    cellWidthDp >= 190 -> 168
                    cellWidthDp >= 160 -> 154
                    cellWidthDp >= 135 -> 142
                    cellWidthDp >= 115 -> 132
                    cellWidthDp >= 95 -> 120
                    else -> 108
                }
            } else 82
            view.layoutParams = (view.layoutParams ?: AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))).apply {
                height = dp(heightDp)
            }
            amountTv.textSize = if (isGrid) {
                when {
                    cellWidthDp >= 190 -> 28f
                    cellWidthDp >= 160 -> 25f
                    cellWidthDp >= 135 -> 22f
                    cellWidthDp >= 115 -> 20f
                    cellWidthDp >= 95 -> 18f
                    else -> 15f
                }
            } else 28f
            channelTv.textSize = if (isGrid) {
                when {
                    cellWidthDp >= 160 -> 14f
                    cellWidthDp >= 115 -> 12f
                    else -> 10f
                }
            } else 12f
            timeTv.textSize = if (isGrid && cellWidthDp < 95) 10f else 12f
            return view
        }
    }
}
