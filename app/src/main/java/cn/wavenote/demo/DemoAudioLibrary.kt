package cn.wavenote.demo

data class DemoAudioFile(val name: String, val size: Long, val mode: Int) { val key get() = "$mode:$size:$name" }
data class DemoRecordingValue(val state: Int, val name: String?, val mode: Int?)
data class DemoAudioRow(val file: DemoAudioFile, var received: Long = 0, var status: String = "等待同步", var localPath: String? = null)

/** 仅通过注入的公开 SDK 适配器顺序工作；连接和每个请求分别隔离旧/重复回调。 */
class DemoAudioLibrary {
    var readRecording: ((DemoRecordingValue?, String?) -> Unit) -> Unit = {}
    var count: (Int, (Int?, String?) -> Unit) -> Unit = { _, _ -> }
    var page: (Int, Int, (List<DemoAudioFile>?, String?) -> Unit) -> Unit = { _, _, _ -> }
    var download: (DemoAudioFile, (Long) -> Unit, (String?, String?) -> Unit) -> (() -> Unit) = { _, _, _ -> {} }
    var changed: (() -> Unit)? = null
    var finished: (() -> Unit)? = null
    var rows = emptyList<DemoAudioRow>(); private set
    var busy = false; private set
    var recording = DemoRecordingValue(0, null, null); private set
    var message = "连接后检查录音状态"; private set
    private var generation = 0
    private var request = 0
    private var stopRequested = false
    private var cancelDownload: (() -> Unit)? = null
    private val collected = mutableListOf<DemoAudioFile>()
    val isRecording get() = recording.state == 2 || recording.state == 3
    fun invalidate() {
        generation++; request++; cancelDownload = null; busy = false; stopRequested = false
        rows = emptyList(); collected.clear(); recording = DemoRecordingValue(0, null, null); message = "连接已断开，重新连接后同步"
    }
    fun observe(value: DemoRecordingValue) {
        recording = value
        if (value.state != 1) {
            if (busy) { stopRequested = true; cancelDownload?.invoke() }
            message = if (isRecording) "${if (value.state == 3) "录音已暂停" else "正在录音"} · 文件同步已暂停" else "录音状态未知，不同步文件"
        }
        changed?.invoke()
    }
    fun start(): Boolean {
        if (busy || isRecording) return false
        generation++; busy = true; stopRequested = false; collected.clear()
        message = "正在确认录音状态…"; changed?.invoke()
        val ticket = next()
        readRecording { value, error ->
            if (accept(ticket)) {
                if (error != null) end(error)
                else if (value == null) end("录音状态应答缺失")
                else if (stopRequested) end(message)
                else { observe(value); if (stopRequested || value.state != 1) end(message) else listMode(1) }
            }
        }
        return true
    }
    fun stop() { if (busy) { stopRequested = true; message = "正在停止同步…"; changed?.invoke(); cancelDownload?.invoke() } }
    private fun next() = generation to ++request
    private fun accept(ticket: Pair<Int, Int>): Boolean {
        if (!busy || generation != ticket.first || request != ticket.second) return false
        request++; return true
    }
    private fun proceed(): Boolean {
        if (stopRequested || recording.state != 1) { end(if (isRecording) "录音中，文件同步已暂停" else "同步已停止，可点击重新同步"); return false }
        return true
    }
    private fun listMode(mode: Int) {
        if (!proceed()) return
        message = "正在获取 ${if (mode == 1) "Note" else "Call"} 文件列表…"; changed?.invoke()
        val ticket = next()
        count(mode) { value, error ->
            if (accept(ticket) && proceed()) {
                if (error != null) end(error)
                else if (value == null || value !in 0..1_000_000) end("文件数量应答非法")
                else listPage(mode, 0, value, emptyList())
            }
        }
    }
    private fun listPage(mode: Int, index: Int, expected: Int, files: List<DemoAudioFile>) {
        if (!proceed()) return
        if (files.size == expected) {
            collected.addAll(files)
            if (mode == 1) listMode(2)
            else { val old = rows.associateBy { it.file.key }; rows = collected.map { old[it.key] ?: DemoAudioRow(it) }; changed?.invoke(); downloadNext(0) }
            return
        }
        val ticket = next()
        page(mode, index) { values, error ->
            if (accept(ticket) && proceed()) {
                if (error != null) end(error)
                else if (values == null || values.isEmpty() || values.size > 5 || files.size + values.size > expected ||
                    values.any { it.mode != mode || it.size < 0 } || (files + values).map { it.name }.toSet().size != files.size + values.size) end("文件列表变化或出现重复页，请重新同步")
                else listPage(mode, index + 1, expected, files + values)
            }
        }
    }
    private fun downloadNext(index: Int) {
        if (!proceed()) return
        if (index >= rows.size) { end(if (rows.isEmpty()) "设备暂无录音文件" else "同步完成，点击播放本地音频"); return }
        val file = rows[index].file
        if (file.size == 0L) { rows[index].status = "空文件，无法播放"; changed?.invoke(); downloadNext(index + 1); return }
        rows[index].status = "正在同步"; rows[index].received = 0; rows[index].localPath = null
        message = "正在同步 ${index + 1}/${rows.size}"; changed?.invoke()
        val ticket = next()
        cancelDownload = download(file, { bytes ->
            if (busy && generation == ticket.first && request == ticket.second) { rows[index].received = bytes.coerceIn(0, file.size); changed?.invoke() }
        }, { path, error ->
            if (accept(ticket)) {
                cancelDownload = null
                if (error != null) { rows[index].status = if (isRecording) "录音开始，下载中断" else error; end(if (stopRequested) "同步已暂停，未完成文件不会用于播放" else error) }
                else if (path == null) { rows[index].status = "缺少本地文件"; end("下载未交付完整文件") }
                else { rows[index].localPath = path; rows[index].received = file.size; rows[index].status = "已下载"; changed?.invoke(); downloadNext(index + 1) }
            }
        })
    }
    private fun end(text: String) { busy = false; cancelDownload = null; message = text; changed?.invoke(); finished?.invoke() }
}

/** 文件名 Unix 秒时间戳仅用于估算；暂停时冻结，使用单调时钟累计连接后的时间。 */
class DemoRecordingClock {
    private var key: String? = null
    private var state = 0
    private var elapsed = 0.0
    private var anchor = 0.0
    var estimated = false; private set
    fun update(value: DemoRecordingValue, wall: Double = System.currentTimeMillis() / 1000.0, uptime: Double = System.nanoTime() / 1e9) {
        if (value.state != 2 && value.state != 3) { key = null; state = value.state; elapsed = 0.0; estimated = false; return }
        val newKey = "${value.mode ?: 0}:${value.name ?: ""}"
        if (key != newKey) {
            key = newKey; elapsed = 0.0
            val stamp = value.name?.substringBefore('.')?.toLongOrNull()?.toDouble()
            estimated = stamp != null && stamp >= 1746057600 && stamp <= wall
            if (estimated) elapsed = wall - stamp!!
        } else if (state == 2) elapsed += (uptime - anchor).coerceAtLeast(0.0)
        anchor = uptime; state = value.state
    }
    fun seconds(uptime: Double = System.nanoTime() / 1e9) = (elapsed + if (state == 2) (uptime - anchor).coerceAtLeast(0.0) else 0.0).toLong()
    companion object { fun text(seconds: Long) = java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) }
}
