package com.smartpay.alipaylistener

import android.app.Activity
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
import android.view.Window
import android.widget.*
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var listView: ListView
    private lateinit var adapter: PaymentAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvClock: TextView
    private lateinit var panelData: View
    private lateinit var panelDevice: View
    private lateinit var panelSettings: View
    private lateinit var bottomBar: View
    private lateinit var topActions: View
    private lateinit var etPairCode: EditText
    private lateinit var tvLog: TextView
    private val prefsName = "alipay_listener_prefs"
    private val paymentsKey = "recent_payments"
    private val handler = Handler(Looper.getMainLooper())
    private val allPayments = mutableListOf<Payment>() // 完整保存所有历史订单，不再截断10条
    private val filteredPayments = mutableListOf<Payment>() // 当前选中日期显示的订单
    private val selectedDay = Calendar.getInstance() // 当前选中查看的日期，默认今天
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private lateinit var tvSelectedDate: TextView
    private var tts: TextToSpeech? = null
    private var ttsEnabled = true // 默认开启软件TTS播报

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
        // 初始化本地TTS播报
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
        MqttClientManager.setPaymentCallback { amount, channel -> runOnUiThread { addPayment(amount, channel) } }
        MqttClientManager.setStatusCallback { _, _ -> runOnUiThread { updateStatus() } }
    }

    private fun bindViews() {
        listView = findViewById(R.id.lv_payments)
        adapter = PaymentAdapter(this, filteredPayments)
        listView.adapter = adapter
        tvStatus = findViewById(R.id.tv_status_top)
        tvClock = findViewById(R.id.tv_clock)
        panelData = findViewById(R.id.panel_data)
        panelDevice = findViewById(R.id.panel_device)
        panelSettings = findViewById(R.id.panel_settings)
        bottomBar = findViewById(R.id.bottom_bar)
        topActions = findViewById(R.id.top_actions)
        etPairCode = findViewById(R.id.et_pair_code)
        tvLog = findViewById(R.id.tv_log)
        // 店铺名改为长风照相馆
        findViewById<TextView>(R.id.tv_shop_name).text = "长风照相馆"
        // 日期切换控件
        tvSelectedDate = findViewById(R.id.tv_selected_date)
        findViewById<TextView>(R.id.btn_prev_day).setOnClickListener {
            selectedDay.add(Calendar.DAY_OF_MONTH, -1)
            refreshDayList()
        }
        findViewById<TextView>(R.id.btn_next_day).setOnClickListener {
            val today = Calendar.getInstance()
            if (selectedDay.before(today) || selectedDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                selectedDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                selectedDay.add(Calendar.DAY_OF_MONTH, 1)
                refreshDayList()
            }
        }
        etPairCode.setText(getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("pair_code", ""))
        findViewById<TextView>(R.id.tab_data).setOnClickListener { showPanel(0) }
        findViewById<TextView>(R.id.tab_device).setOnClickListener { showPanel(1) }
        findViewById<TextView>(R.id.tab_settings).setOnClickListener { showPanel(2) }
        // 复制全部日志到剪贴板
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener {
            val allLogs = LogManager.getAllLogs().joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("收款日志", allLogs))
            android.widget.Toast.makeText(this, "日志已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPanel(index: Int) {
        panelData.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelDevice.visibility = if (index == 1) View.VISIBLE else View.GONE
        panelSettings.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun setupActions() {
        findViewById<ImageButton>(R.id.btn_fullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<ImageButton>(R.id.btn_orientation).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            val code = etPairCode.text.toString().trim()
            if (code.length != 6) { Toast.makeText(this, "请输入6位配对码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("pair_code", code).apply()
            val i = Intent(this, KeepAliveService::class.java).putExtra("pair_code", code)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "监听服务已启动", Toast.LENGTH_SHORT).show()
            updateStatus()
        }
        findViewById<Button>(R.id.btn_open_notification_access).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }

    private fun addPayment(amount: String, channel: String) {
        if (amount.trim().isEmpty()) return
        val finalChannel = when {
            channel.isEmpty() -> "银行卡收款"
            channel.contains("微信") -> "微信支付"
            channel.contains("支付宝") -> "支付宝"
            else -> channel
        }
        val newPayment = Payment(amount.trim(), finalChannel, System.currentTimeMillis())
        allPayments.add(0, newPayment)
        savePayments()
        // 如果当前选中的是今天，刷新列表
        val today = Calendar.getInstance()
        if (selectedDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            selectedDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            refreshDayList()
        }
        // 本地TTS播报
        if (ttsEnabled) {
            tts?.speak("${finalChannel}到账${amount}元", TextToSpeech.QUEUE_FLUSH, null, "payment_${System.currentTimeMillis()}")
        }
    }

    private fun refreshDayList() {
        // 按选中日期过滤当天订单，按时间倒序
        filteredPayments.clear()
        val dayStr = dayFormat.format(selectedDay.time)
        allPayments.forEach { p ->
            val pDay = dayFormat.format(Date(p.time))
            if (pDay == dayStr) filteredPayments.add(p)
        }
        // 按时间倒序，最新的排前面
        filteredPayments.sortByDescending { it.time }
        tvSelectedDate.text = dayStr
        adapter.notifyDataSetChanged()
    }

    private fun savePayments() {
        val value = allPayments.joinToString(";") { it.time.toString() + "|" + it.amount + "|" + it.channel }
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(paymentsKey, value).apply()
    }

    private fun loadPayments() {
        val value = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(paymentsKey, "") ?: return
        if (value.isEmpty()) return
        value.split(";").forEach {
            val p = it.split("|")
            if (p.size == 3) allPayments.add(Payment(p[1], p[2], p[0].toLongOrNull() ?: System.currentTimeMillis()))
        }
        // 加载完成后刷新今天的列表
        refreshDayList()
    }

    private fun updateStatus() {
        val paired = MqttClientManager.isConnected() && MqttClientManager.isPaired()
        tvStatus.text = if (paired) "已连接" else "未连接"
        tvStatus.setTextColor(if (paired) 0xFF62D88F.toInt() else 0xFFF2B84B.toInt())
        findViewById<TextView>(R.id.tv_device_status).text = if (paired) "MQTT：已连接并已配对" else "MQTT：等待连接或配对"
        findViewById<TextView>(R.id.tv_notification_status).text = if (isNotificationServiceEnabled()) "通知监听：已开启" else "通知监听：未开启"
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (TextUtils.isEmpty(flat)) return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun startClock() {
        handler.post(object : Runnable {
            override fun run() { tvClock.text = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date()); handler.postDelayed(this, 1000) }
        })
    }

    private fun toggleFullscreen() {
        val decor = window.decorView
        val flags = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val full = (decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
        decor.systemUiVisibility = if (full) View.SYSTEM_UI_FLAG_LAYOUT_STABLE else flags
        bottomBar.visibility = if (full) View.VISIBLE else View.GONE
        // 全屏时仍保留右上角操作区，让“眼睛”按钮可再次点击退出全屏
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

    private inner class PaymentAdapter(private val context: Context, private val items: List<Payment>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.item_payment, parent, false)
            val item = items[position]
            v.findViewById<TextView>(R.id.tv_payment_amount).text = "¥" + item.amount
            val channelTv = v.findViewById<TextView>(R.id.tv_payment_channel)
            channelTv.text = item.channel
            v.findViewById<TextView>(R.id.tv_payment_time).text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.time))
            // 渠道颜色：微信绿、支付宝蓝、其他银行红
            val channelColor = when {
                item.channel.contains("微信") -> 0xFF07C160.toInt()
                item.channel.contains("支付宝") -> 0xFF1677FF.toInt()
                else -> 0xFFF43F5E.toInt()
            }
            channelTv.setTextColor(channelColor)
            val amountTv = v.findViewById<TextView>(R.id.tv_payment_amount)
            // 最新一笔高亮：只有当前选中是今天，且是列表第一笔时高亮
            val today = Calendar.getInstance()
            val isToday = selectedDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    selectedDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            if (position == 0 && isToday) {
                v.setBackgroundColor(0x331677FF.toInt()) // 蓝色半透明高亮背景
                amountTv.setTextColor(channelColor) // 大字金额用对应渠道色
                amountTv.textSize = 28f
            } else {
                v.setBackgroundColor(0x00000000) // 透明背景
                amountTv.setTextColor(channelColor) // 普通订单大字金额也用对应渠道色
                amountTv.textSize = 22f
            }
            return v
        }
    }
}
