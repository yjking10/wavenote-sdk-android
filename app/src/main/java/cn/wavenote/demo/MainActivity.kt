package cn.wavenote.demo

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*

/** 独立二进制 SDK 演示：只通过公共 API 进行连接与设置。 */
// Demo 固定中文界面；Android 13+ 使用上方注册的原生返回回调。
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var model: DemoController
    private var page = "home"
    private var draft = ""
    private var infoTitle = ""
    private var infoText = ""
    private var dialog: AlertDialog? = null
    private val accent = Color.rgb(23, 108, 131)
    private val ink = Color.rgb(28, 37, 46)
    private val muted = Color.rgb(90, 103, 115)
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private var lastPage = ""
    private var playbackTime: TextView? = null
    private var playbackProgress: ProgressBar? = null
    private fun updatePlaybackProgress() {
        playbackTime?.text = model.player.timeText
        playbackProgress?.progress = (model.player.fraction * 1000).toInt()
        playbackProgress?.contentDescription = "播放进度 ${model.player.timeText}"
    }
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { if (page == "recording") render(); timer.postDelayed(this, 1000) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = DemoController(applicationContext)
        model.player.progressChanged = { updatePlaybackProgress() }
        model.changed = {
            if (!model.flow.ready && page != "home") { page = "home"; dialog?.dismiss(); dialog = null }
            render()
        }
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        render()
    }
    override fun onStart() { super.onStart(); timer.postDelayed(tick, 1000) }
    override fun onStop() { timer.removeCallbacks(tick); model.player.stop(); super.onStop() }
    override fun onDestroy() { model.dispose(); super.onDestroy() }
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Compatibility with Android 10–12") override fun onBackPressed() { back() }
    private fun back() { when (page) { "home" -> finish(); "settings", "recording" -> { page = "home"; render() }; else -> { page = "settings"; render() } } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 16f, color: Int = ink): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); setPadding(dp(20), dp(12), dp(20), dp(12))
    }
    private fun section(text: String) { body.addView(label(text, 13f, accent).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(20), dp(26), dp(20), dp(8)) }) }
    private fun note(text: String) { body.addView(label(text, 13f, muted)) }
    private fun row(title: String, value: String = "", enabled: Boolean = true, destructive: Boolean = false, action: (() -> Unit)? = null) {
        val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); minimumHeight = dp(58) }
        item.addView(label(title, 16f, if (destructive) Color.rgb(169, 38, 47) else ink))
        if (value.isNotEmpty()) item.addView(label(value, 14f, muted).apply { setPadding(dp(20), 0, dp(20), dp(14)) })
        item.isEnabled = enabled; item.alpha = if (enabled) 1f else 0.55f
        if (action != null) { item.isClickable = true; item.setOnClickListener { if (enabled) action() } }
        body.addView(item); body.addView(View(this).apply { setBackgroundColor(Color.rgb(232, 236, 239)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    private fun navigate(value: String) { if (model.flow.ready) { page = value; render() } }
    private fun render() {
        if (isFinishing || isDestroyed) return
        val oldScroll = if (::scroll.isInitialized && lastPage == page) scroll.scrollY else 0
        lastPage = page
        playbackTime = null; playbackProgress = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(242, 244, 246)) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()) else null
            if (bars != null) v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            else { @Suppress("DEPRECATION") v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom) }
            insets
        }
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE) }
        if (page != "home") nav.addView(Button(this).apply { text = "返回"; setOnClickListener { back() } })
        val title = when (page) { "home" -> if (model.flow.ready) model.sdk.connectedDevice?.serialNumber ?: "WaveNote" else "WaveNote"; "recording" -> "当前录音"; "settings" -> "设备设置"; "mic" -> "麦克风增益"; "vcs" -> "振动传感器增益"; "power" -> "自动关机"; else -> infoTitle }
        nav.addView(label(title, if (page == "home" && model.flow.ready) 14f else 20f).apply {
            typeface = if (page == "home" && model.flow.ready) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
            if (page == "home" && model.flow.ready) { setTextColor(accent); contentDescription = "已连接设备，点击设置：$title"; setOnClickListener { navigate("settings"); model.refresh() } }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(nav)
        scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        when (page) { "home" -> home(); "recording" -> recording(); "settings" -> settings(); "mic", "vcs" -> gain(); "power" -> power(); else -> note(infoText) }
        scroll.post { scroll.scrollTo(0, oldScroll) }; root.requestApplyInsets()
    }
    private fun home() {
        section("连接设备")
        row("开始扫描", enabled = !model.flow.busy && !model.flow.ready) { requestScan() }
        row(model.bluetooth) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        note(model.status)
        note("演示身份：本地模拟。绑定仅在本机保存，两端不共享。")
        if (model.flow.ready) audioLibrary()
        if (!model.flow.showsNearby) return
        section("附近设备")
        if (model.devices.isEmpty()) note("扫描后，附近的 Note 设备会显示在这里。")
        model.devices.forEach { device ->
            row(device.serialNumber, "${device.name ?: "Note"} · ${device.rssi} dBm · ${if (model.identity.isBound(device.serialNumber)) "已绑定" else "未绑定"}", enabled = !model.flow.busy && !model.flow.ready) { model.select(device) }
        }
    }
    private fun audioLibrary() {
        if (model.library.isRecording) {
            section("当前录音")
            row(if (model.library.recording.state == 3) "录音已暂停" else "正在录音", "点击查看当前文件名和录音时长 · 文件同步已暂停") { navigate("recording") }
            return
        }
        section("录音文件")
        row(if (model.library.busy) "停止同步" else "重新同步文件", model.library.message, enabled = model.library.busy || !model.flow.busy) {
            if (model.library.busy) model.library.stop() else model.syncFiles()
        }
        model.library.rows.forEach(::audioFileRow)
        if (model.player.message.isNotEmpty()) note(model.player.message)
        note("音频仅保存在本机，不删除设备文件。下载中停止同步可能断开蓝牙，需要重新连接。")
    }
    /** 与 iOS 文件行一致：左侧文件信息，右侧播放按钮、时间和细进度条。 */
    private fun audioFileRow(item: DemoAudioRow) {
        val path = item.localPath
        val active = path != null && model.player.path == path && !model.player.preparing
        val percent = if (item.file.size > 0) (item.received.toDouble() / item.file.size * 100).toInt() else 0
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE); setPadding(dp(20), dp(14), dp(20), dp(14))
            minimumHeight = dp(if (active) 112 else 74)
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(label(item.file.name).apply { setPadding(0, 0, 0, dp(4)) })
        details.addView(label("${if (item.file.mode == 1) "Note" else "Call"} · ${item.status} · $percent%\n${android.text.format.Formatter.formatFileSize(this, item.received)} / ${android.text.format.Formatter.formatFileSize(this, item.file.size)}", 13f, muted).apply { setPadding(0, 0, 0, 0) })
        container.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        val accessory = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        container.addView(accessory, LinearLayout.LayoutParams(dp(if (active) 142 else if (path != null) 64 else 80), -2))
        if (path != null) {
            accessory.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = if (active && model.player.playing) "暂停" else "播放"
                textSize = 16f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                minHeight = dp(48); minimumHeight = dp(48); setPadding(0, 0, 0, 0)
                setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, accent)))
                isEnabled = !model.player.preparing
                contentDescription = "$text ${item.file.name}"
                setOnClickListener { model.player.toggle(path) }
            }, LinearLayout.LayoutParams(-1, -2))
            if (active) {
                playbackTime = label(model.player.timeText, 11f, muted).apply {
                    typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                }
                accessory.addView(playbackTime, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                playbackProgress = audioProgressBar()
                accessory.addView(playbackProgress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
                updatePlaybackProgress()
            }
        } else {
            accessory.addView(audioProgressBar().apply {
                progress = percent * 10; contentDescription = "下载进度 $percent% ${item.file.name}"
            }, LinearLayout.LayoutParams(-1, dp(4)))
        }
        body.addView(container)
        body.addView(View(this).apply { setBackgroundColor(Color.rgb(232, 236, 239)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    private fun audioProgressBar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(225, 229, 234))
    }
    private fun recording() {
        section("当前录音")
        val value = model.library.recording
        row("状态", when (value.state) { 2 -> "正在录音"; 3 -> "录音已暂停"; 1 -> "录音已停止"; else -> "未知" })
        row("文件名称", value.name ?: "未知")
        row(if (model.recordingClock.estimated) "录音时长（估算）" else "本次连接已观察时长", if (model.library.isRecording) DemoRecordingClock.text(model.recordingClock.seconds()) else "—")
        note("设备没有提供总时长字段。文件名含有效 Unix 秒时间戳时据此估算，并扣除本次连接观察到的暂停；连接前的暂停无法还原。无法解析时仅累计本次观察时长。停止录音后自动同步文件。")
    }
    private fun requestScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else model.scan()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) { model.status = "权限已授予，请点击开始扫描。" }
            else model.status = "蓝牙权限未授予，请点击蓝牙状态前往系统设置，允许权限后重试。"
            render()
        }
    }
    private fun info(title: String, text: String) { infoTitle = title; infoText = text; navigate("info") }
    private fun settings() {
        val s = model.snapshot; val idle = !model.flow.busy
        section("通用")
        row("刷新设备状态", enabled = idle) { model.refresh() }; note(model.status)
        row("设备名称", s?.deviceName ?: model.sdk.connectedDevice?.name ?: "未知")
        row("SN", model.sdk.connectedDevice?.serialNumber ?: "未知")
        val mode = when (model.mode) { 1 -> "Note 模式"; 2 -> "Call 模式"; else -> "未知" }
        row("模式", mode) { info("录音模式", "当前：$mode\n\nNote 模式用于现场录音；Call 模式用于通话录音。请使用设备物理开关切换，页面随设备状态更新。") }
        val charging = when (s?.charging) { 1 -> "充电中"; 0 -> "未充电"; else -> "未知" }
        row("电量", s?.batteryLevel?.let { "$it% · $charging" } ?: "未知") { info("电量", "电量：${s?.batteryLevel ?: "未知"}\n充电状态：$charging\n\n电量较低时请及时充电。") }
        val disk = if (s?.storageUsed != null && s.storageTotal != null && s.storageTotal!! > 0) "${s.storageUsed} / ${s.storageTotal}（${(s.storageUsed!!.toDouble() / s.storageTotal!! * 100).toInt()}%）" else "未知"
        row("存储", disk) { info("设备存储", "已用 / 总量：$disk\n\n容量保留设备原始单位。此页面仅查看使用情况，不清空设备内容。") }
        row("固件版本", s?.firmwareVersion ?: "未知")
        row("自动关机", DemoFlow.minutes(s?.autoPowerOffMinutes), enabled = idle) { navigate("power") }
        section("隐私与安全")
        body.addView(Switch(this).apply {
            text = "USB 访问 · ${DemoValues.usb(s?.usbEnabled)}"
            setPadding(dp(20), dp(16), dp(20), dp(16)); isChecked = s?.usbEnabled == 1; isEnabled = idle && DemoValues.canSetUSB(s?.usbEnabled)
            setOnCheckedChangeListener { _, checked -> model.control { model.sdk.deviceSettings.setUSBEnabled(checked, it) } }
        })
        note("开启后允许通过 USB 访问设备内容。此状态不表示线缆是否连接。")
        section("高级录音设置")
        row("麦克风增益", s?.microphoneGain?.toString() ?: "未知", enabled = idle) { draft = s?.microphoneGain?.toString() ?: ""; navigate("mic") }
        row("振动传感器增益", s?.vibrationGain?.toString() ?: "未知", enabled = idle) { draft = s?.vibrationGain?.toString() ?: ""; navigate("vcs") }
        note("原始整数 0–255，数值不代表分贝或百分比。")
        section("连接管理")
        row("断开连接", enabled = idle, destructive = true) { model.disconnect() }
        row("解绑设备", enabled = idle, destructive = true) { confirm("解绑设备", "仅解除本机演示账户归属，不清空设备内容。") { model.unbind() } }
        note("演示身份：本地模拟")
    }
    private fun gain() {
        section("原始增益值")
        note("请输入 0–255 整数，点击保存后等待设备回读确认。")
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); textSize = 28f; typeface = Typeface.MONOSPACE; hint = "0–255"; setText(draft); isEnabled = !model.flow.busy }
        body.addView(input, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), dp(16), dp(20), dp(16)) })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s.toString() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val steps = LinearLayout(this)
        for ((label, delta) in listOf("−1" to -1, "+1" to 1)) steps.addView(Button(this).apply {
            text = label; isEnabled = !model.flow.busy
            setOnClickListener { input.setText(((DemoFlow.gain(draft) ?: 0) + delta).coerceIn(0, 255).toString()) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(steps)
        row("保存", enabled = !model.flow.busy) {
            val value = DemoFlow.gain(draft)
            if (value == null) { model.status = "请输入 0–255 范围内的整数。"; render() }
            else { val microphone = page == "mic"; model.control { if (microphone) model.sdk.deviceSettings.setMicrophoneGain(value, it) else model.sdk.deviceSettings.setVibrationGain(value, it) } }
        }
        note(model.status)
    }
    private fun power() {
        section("空闲等待时间")
        note("当前：${DemoFlow.minutes(model.snapshot?.autoPowerOffMinutes)}")
        listOf(1, 15, 30, 60, 300, 0).forEach { value ->
            row(DemoFlow.minutes(value), if (model.snapshot?.autoPowerOffMinutes == value) "已选择" else "", enabled = !model.flow.busy) { model.control { model.sdk.deviceSettings.setAutoPowerOff(value, it) } }
        }
        note(model.status)
        row("立即关机", enabled = !model.flow.busy, destructive = true) { confirm("立即关机？", "将中断设备连接。若只有断连通知，结果仍可能未确认。") { model.control(shutdown = true) { model.sdk.maintenance.shutdown(it) } } }
    }
    private fun confirm(title: String, text: String, action: () -> Unit) {
        dialog = AlertDialog.Builder(this).setTitle(title).setMessage(text).setNegativeButton("取消", null).setPositiveButton("确认") { _, _ -> if (model.flow.ready && !model.flow.busy) action() }.show()
    }
}
