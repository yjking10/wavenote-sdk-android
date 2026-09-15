package cn.wavenote.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.wavenote.sdk.*
import java.security.MessageDigest

class DemoIdentityProvider(context: Context) : WaveNoteIdentityProvider {
    private val prefs = context.getSharedPreferences("demo.ownership", Context.MODE_PRIVATE)
    private val store = DemoOwnershipStore({ prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap() },
        { values -> prefs.edit().clear().also { edit -> values.forEach { (key, value) -> edit.putString(key, value) } }.apply() })
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun isBound(sn: String) = store.owner(hash(sn)) == hash("demo-user")
    override fun checkOwnership(serialNumber: String, apiKey: String, userIdentifier: String, completion: WaveNoteCompletion<WaveNoteOwnership>) {
        val owner = store.owner(hash(serialNumber))
        completion.complete(when (owner) { null -> WaveNoteOwnership.UNBOUND; hash(userIdentifier) -> WaveNoteOwnership.CURRENT_USER; else -> WaveNoteOwnership.ANOTHER_USER }, null)
    }
    override fun bind(serialNumber: String, apiKey: String, userIdentifier: String, completion: WaveNoteControlCompletion) {
        completion.complete(if (store.bind(hash(serialNumber), hash(userIdentifier))) null else WaveNoteError(WaveNoteErrorCode.DEVICE_BOUND_TO_ANOTHER_USER, "bind"))
    }
    override fun unbind(serialNumber: String, apiKey: String, userIdentifier: String, completion: WaveNoteControlCompletion) {
        completion.complete(if (store.unbind(hash(serialNumber), hash(userIdentifier))) null else WaveNoteError(WaveNoteErrorCode.CLOUD_UNBIND_FAILED, "unbind"))
    }
}

class DemoController(context: Context) : WaveNoteSDKDelegate, WaveNoteDeviceSettingsDelegate, WaveNoteRecordingDelegate, WaveNoteFilesDelegate {
    val sdk = WaveNoteSDK.getInstance(context)
    val identity = DemoIdentityProvider(context)
    val flow = DemoFlow()
    val library = DemoAudioLibrary()
    val recordingClock = DemoRecordingClock()
    val player = DemoNativePlayer(context.applicationContext)
    private val fileQueue = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val store = DemoAudioStore(java.io.File(context.filesDir, "recordings"))
    private var libraryToken: Int? = null
    private var audioSession = 0
    private var scheduledSync = 0
    private var progressID: java.util.UUID? = null
    private var progressCallback: ((Long) -> Unit)? = null
    var changed: (() -> Unit)? = null
    var devices = emptyList<WaveNoteDiscoveredDevice>(); private set
    var snapshot: WaveNoteSettingsSnapshot? = null; private set
    var mode: Int? = null; private set
    var status = "点击开始扫描，选择身边的 Note 设备。"
    var bluetooth = "正在检查蓝牙状态"; private set
    private val handler = Handler(Looper.getMainLooper())
    private var scanGeneration = 0
    private var scanning = false
    private var unbindCompleted = false
    init {
        sdk.delegate = this; sdk.deviceSettings.delegate = this; sdk.recording.delegate = this; sdk.files.delegate = this
        configureLibrary()
        // Demo 在 Debug / Release 均默认输出 SDK 脱敏日志到 Logcat。
        sdk.openLog(true)
        sdk.configure(WaveNoteSDKConfiguration("demo-simulation-not-a-credential", "demo-user", enableAutoReconnect = false, identityProvider = identity))
    }
    fun dispose() { changed = null; resetAudio(); player.dispose(); fileQueue.shutdown(); sdk.files.delegate = null; handler.removeCallbacksAndMessages(null); sdk.disconnectDevice(); sdk.delegate = null; sdk.deviceSettings.delegate = null; sdk.recording.delegate = null }
    fun scan() {
        if (flow.busy || flow.ready) return
        // startScanning 会重新读取系统权限与蓝牙状态，不能用上次 UNAUTHORIZED 快照阻止重试。
        flow.invalidate(); scanGeneration++; val token = scanGeneration
        devices = emptyList(); scanning = true; status = "正在扫描…"; changed?.invoke(); sdk.startScanning()
        handler.postDelayed({
            if (scanGeneration == token && scanning) {
                scanning = false; status = if (devices.isEmpty()) "未发现设备，请靠近设备后重新扫描。" else "扫描完成，请选择设备。"; changed?.invoke()
            }
        }, 8200)
    }
    fun select(device: WaveNoteDiscoveredDevice) {
        if (System.currentTimeMillis() - device.lastSeenMillis > 30000) { status = "扫描结果已过期，请重新扫描后选择设备。"; changed?.invoke(); return }
        val (token, route) = flow.select(identity.isBound(device.serialNumber), true) ?: return
        scanning = false; scanGeneration++
        status = if (route == DemoFlow.Route.BIND) "正在绑定…" else "正在连接…"; changed?.invoke()
        if (route == DemoFlow.Route.CONNECT) { sdk.connect(device); return }
        sdk.bind(device) { _, error ->
            if (flow.accepts(token)) {
                if (flow.bound(token, error == null)) { status = "绑定完成，正在连接…"; changed?.invoke(); sdk.connect(device) }
                else { status = error?.let(::errorText) ?: "绑定未完成"; changed?.invoke() }
            }
        }
    }
    fun refresh() {
        val token = flow.beginSetting() ?: return
        status = "正在读取设备设置…"; changed?.invoke()
        val items = listOf(WaveNoteSetting.HARDWARE, WaveNoteSetting.BATTERY, WaveNoteSetting.CHARGING, WaveNoteSetting.STORAGE,
            WaveNoteSetting.RECORDING, WaveNoteSetting.MICROPHONE_GAIN, WaveNoteSetting.VIBRATION_GAIN, WaveNoteSetting.AUTO_POWER_OFF)
        DemoReadSequence.run<WaveNoteSetting, WaveNoteError>(items, { flow.accepts(token) && flow.ready }, { item, done ->
            sdk.deviceSettings.query(item) { value, error ->
                if (flow.accepts(token) && flow.ready) {
                    if (error != null) done(error)
                    else if (value == null) done(WaveNoteError(WaveNoteErrorCode.INVALID_DEVICE_RESPONSE, "query"))
                    else { snapshot = value; mode = sdk.recording.snapshot.mode; changed?.invoke(); done(null) }
                }
            }
        }, { error ->
            if (flow.finish(token)) { status = error?.let(::errorText) ?: "设备设置已更新"; changed?.invoke() }
        })
    }

    fun control(shutdown: Boolean = false, action: (WaveNoteControlCompletion) -> Unit) {
        val token = flow.beginSetting() ?: run { status = "设备忙碌，请等待当前操作完成。"; changed?.invoke(); return }
        status = if (shutdown) "正在请求关机，等待设备确认…" else "正在保存，等待设备回读…"; changed?.invoke()
        action(WaveNoteControlCompletion { error ->
            if (flow.accepts(token) || (shutdown && !flow.ready && flow.generation == token + 1)) {
                flow.finish(token)
                status = error?.let(::errorText) ?: if (shutdown) "设备已确认关机请求" else "已保存并确认设备状态"
                changed?.invoke()
            }
        })
    }
    fun disconnect() { if (!flow.busy) sdk.disconnectDevice() }
    fun unbind() {
        val token = flow.beginSetting() ?: return
        status = "正在解绑…"; changed?.invoke()
        sdk.unbindCurrentDevice { error ->
            if (flow.accepts(token) || (!flow.ready && flow.generation == token + 1)) {
                flow.finish(token); status = error?.let(::errorText) ?: "已解绑，设备内容保留。"; changed?.invoke()
            }
        }
    }
    override fun didUpdateBluetoothState(sdk: WaveNoteSDK, state: WaveNoteBluetoothState) {
        bluetooth = listOf("蓝牙状态未知", "蓝牙未授权 · 可前往系统设置", "此设备不支持蓝牙", "蓝牙已关闭", "蓝牙已开启", "蓝牙正在重置")[state.value]; changed?.invoke()
    }
    override fun didUpdateDiscoveredDevices(sdk: WaveNoteSDK, devices: List<WaveNoteDiscoveredDevice>) { this.devices = devices; changed?.invoke() }
    override fun didChangeConnectionState(sdk: WaveNoteSDK, state: WaveNoteConnectionState, device: WaveNoteDevice?) {
        if (state == WaveNoteConnectionState.DISCONNECTED && flow.selecting) return
        when (state) {
            WaveNoteConnectionState.READY -> { if (!flow.ready) { flow.connected(); snapshot = null; mode = null; audioSession++; library.invalidate(); observeRecording(sdk.recording.snapshot); scheduleSync() }; status = "已连接，点击顶部 SN 查看设备设置。" }
            WaveNoteConnectionState.DISCONNECTED, WaveNoteConnectionState.FAILED -> {
                resetAudio()
                if (state == WaveNoteConnectionState.FAILED) flow.invalidate() else flow.disconnected(); snapshot = null; mode = null
                status = if (unbindCompleted) "已解绑，设备内容保留。" else if (state == WaveNoteConnectionState.FAILED) "连接失败，请重新扫描。" else "已断开，请重新扫描连接。"
                unbindCompleted = false
            }
            else -> { if (flow.ready) { resetAudio(); flow.invalidate(); snapshot = null; mode = null }; if (state in listOf(WaveNoteConnectionState.CONNECTING, WaveNoteConnectionState.DISCOVERING_SERVICES)) status = "正在连接并同步设备状态…" }
        }
        changed?.invoke()
    }
    override fun didChangeUnbindingState(sdk: WaveNoteSDK, state: WaveNoteUnbindingState, device: WaveNoteDevice?) { if (state == WaveNoteUnbindingState.COMPLETED) unbindCompleted = true }
    override fun didReceiveError(sdk: WaveNoteSDK, error: WaveNoteError) {
        if (!flow.ready && flow.busy) flow.invalidate()
        scanning = false; scanGeneration++; status = errorText(error); changed?.invoke()
    }
    override fun didUpdate(settings: WaveNoteDeviceSettings, snapshot: WaveNoteSettingsSnapshot) { if (flow.ready) { this.snapshot = snapshot; changed?.invoke() } }
    override fun didUpdate(recording: WaveNoteRecording, snapshot: WaveNoteRecordingSnapshot) { if (flow.ready) { mode = snapshot.mode; observeRecording(snapshot); changed?.invoke() } }
    private fun configureLibrary() {
        library.changed = { changed?.invoke() }; player.changed = { changed?.invoke() }
        library.finished = { libraryToken?.let { flow.finish(it) }; libraryToken = null; changed?.invoke() }
        library.readRecording = { done -> sdk.recording.refresh { value, error -> done(value?.let { DemoRecordingValue(it.state.value, it.fileName, it.mode) }, error?.let(::errorText)) } }
        library.count = { mode, done -> sdk.files.count(if (mode == 1) WaveNoteRecordMode.NOTE else WaveNoteRecordMode.CALL) { value, error -> done(value, error?.let(::errorText)) } }
        library.page = { mode, index, done -> sdk.files.page(if (mode == 1) WaveNoteRecordMode.NOTE else WaveNoteRecordMode.CALL, index) { values, error ->
            done(values?.map { DemoAudioFile(it.name, it.size, it.mode.value) }, error?.let(::errorText))
        } }
        library.download = { file, progress, done ->
            val sn = sdk.connectedDevice?.serialNumber
            val session = audioSession
            var cancelled = false
            var operation: WaveNoteOperation? = null
            if (sn == null) handler.post { done(null, "连接已失效") }
            else fileQueue.execute {
                val result = runCatching { store.prepare(sn, file) }
                handler.post {
                    if (audioSession == session && flow.ready) {
                        if (cancelled) done(null, "同步已取消")
                        else result.fold(onSuccess = { (destination, cached) ->
                            if (cached) done(destination.path, null)
                            else {
                                operation = sdk.files.download(WaveNoteFile(file.name, file.size, if (file.mode == 1) WaveNoteRecordMode.NOTE else WaveNoteRecordMode.CALL), destination) { output, error ->
                                    if (audioSession == session) {
                                        progressID = null; progressCallback = null
                                        if (error != null) done(null, errorText(error))
                                        else if (output == null) done(null, "下载未交付文件")
                                        else fileQueue.execute {
                                            val saved = runCatching { store.commit(output, sn, file) }
                                            handler.post { if (audioSession == session) saved.fold({ done(output.path, null) }, { done(null, "保存本地索引失败，请检查剩余空间") }) }
                                        }
                                    }
                                }
                                progressID = operation?.identifier; progressCallback = progress
                            }
                        }, onFailure = { done(null, "本地目录不可写或空间不足") })
                    }
                }
            }
            val cancel: () -> Unit = { cancelled = true; operation?.cancel() }
            cancel
        }
    }
    fun syncFiles() {
        if (!flow.ready || library.isRecording || library.busy) return
        val token = flow.beginSetting() ?: return
        scheduledSync++; libraryToken = token
        if (!library.start()) { flow.finish(token); libraryToken = null }
    }
    private fun scheduleSync() {
        scheduledSync++; val ticket = scheduledSync; val session = audioSession
        handler.postDelayed({
            if (flow.ready && audioSession == session && scheduledSync == ticket && !library.isRecording) {
                if (flow.busy) scheduleSync() else syncFiles()
            }
        }, 1500)
    }
    private fun observeRecording(value: WaveNoteRecordingSnapshot) {
        val wasRecording = library.isRecording
        val state = DemoRecordingValue(value.state.value, value.fileName, value.mode)
        recordingClock.update(state); library.observe(state)
        if (library.isRecording) { scheduledSync++; player.stop() }
        if (wasRecording && value.state == WaveNoteRecordingState.STOPPED) scheduleSync()
    }
    private fun resetAudio() {
        audioSession++; scheduledSync++; libraryToken = null; progressID = null; progressCallback = null
        library.invalidate(); recordingClock.update(DemoRecordingValue(0, null, null)); player.stop()
    }
    override fun didUpdate(files: WaveNoteFiles, progress: WaveNoteTransferProgress) {
        if (flow.ready && progress.operationID == progressID) progressCallback?.invoke(progress.receivedBytes)
    }
    companion object {
        fun errorText(error: WaveNoteError) = DemoValues.error(error.code)
    }
}
