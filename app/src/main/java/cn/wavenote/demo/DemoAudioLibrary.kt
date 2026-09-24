package cn.wavenote.demo

data class DemoAudioFile(val name: String, val size: Long, val mode: Int) { val key get() = "$mode:$size:$name" }
data class DemoRecordingValue(val state: Int, val name: String?, val mode: Int?)
data class DemoAudioRow(val file: DemoAudioFile, var received: Long = 0, var status: String = "等待同步", var localPath: String? = null) {
    var kilobytesPerSecond: Double? = null
    val speedText: String get() = if (status in listOf("正在同步", "继续下载")) {
        kilobytesPerSecond?.let { java.lang.String.format(java.util.Locale.ROOT, " · %.1f KB/s", it) } ?: " · — KB/s"
    } else ""
    val logJSON: String get() = org.json.JSONObject()
        .put("file", org.json.JSONObject().put("name", file.name).put("size", file.size).put("mode", file.mode).put("key", file.key))
        .put("received", received).put("status", status).put("localPath", localPath?.replace(Regex("(/wavenote/)[^/]+"), "$1[redacted]") ?: org.json.JSONObject.NULL).toString()
}

/** 仅通过注入的公开 SDK 适配器顺序工作；连接和每个请求分别隔离旧/重复回调。 */
class DemoAudioLibrary {
    var readRecording: ((DemoRecordingValue?, String?) -> Unit) -> Unit = {}
    var count: (Int, (Int?, String?) -> Unit) -> Unit = { _, _ -> }
    var page: (Int, Int, (List<DemoAudioFile>?, String?) -> Unit) -> Unit = { _, _, _ -> }
    /** 列表通过 BLE 读取完成后、首个下载开始前切换传输通道。 */
    var prepareDownloads: ((String?) -> Unit) -> Unit = { it(null) }
    var download: (DemoAudioFile, (Long) -> Unit, (String?, String?) -> Unit) -> (() -> Unit) = { _, _, _ -> {} }
    var deleteLocal: (DemoAudioFile, (String?) -> Unit) -> Unit = { _, completion -> completion("删除本地音频未配置") }
    var changed: (() -> Unit)? = null
    var finished: (() -> Unit)? = null
    var completedRow: ((DemoAudioRow) -> Unit)? = null
    var rows = emptyList<DemoAudioRow>(); private set
    var busy = false; private set
    var recording = DemoRecordingValue(0, null, null); private set
    var message = "连接后检查录音状态"; private set
    private var generation = 0
    private var request = 0
    private var stopRequested = false
    private var cancelDownload: (() -> Unit)? = null
    private val collected = mutableListOf<DemoAudioFile>()
    private val failedThisConnection = mutableSetOf<String>()
    val isRecording get() = recording.state == 2 || recording.state == 3
    /** 只有完整交付到本地的文件计为完成；失败、取消和空文件不计入。 */
    val completedCount get() = rows.count { it.localPath != null }
    val failedCount get() = rows.count { it.status == "同步失败，断点已保留" }
    val totalCount get() = rows.size
    val syncCountText get() = "已完成同步 $completedCount/$totalCount 个文件" + if (failedCount > 0) "，失败 $failedCount 个" else ""
    fun invalidate() {
        generation++; request++; cancelDownload = null; busy = false; stopRequested = false
        rows = emptyList(); collected.clear(); failedThisConnection.clear(); recording = DemoRecordingValue(0, null, null); message = "连接已断开，重新连接后同步"
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
            else {
                val old = rows.associateBy { it.file.key }; rows = collected.map { old[it.key] ?: DemoAudioRow(it) }; changed?.invoke()
                val ticket = next()
                prepareDownloads { error ->
                    if (accept(ticket) && proceed()) {
                        if (error != null) end(error) else downloadNext(0)
                    }
                }
            }
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
        if (index >= rows.size) { end(if (rows.isEmpty()) "设备暂无录音文件" else if (failedCount > 0) "同步结束：成功 $completedCount、失败 $failedCount、总计 $totalCount 个文件" else "同步完成，点击播放本地音频"); return }
        val file = rows[index].file
        if (failedThisConnection.contains(file.key)) { rows[index].status = "同步失败，断点已保留"; changed?.invoke(); downloadNext(index + 1); return }
        if (file.size == 0L) { rows[index].status = "空文件，无法播放"; changed?.invoke(); downloadNext(index + 1); return }
        rows[index].status = "正在同步"; rows[index].received = 0; rows[index].localPath = null
        message = "正在同步第 ${index + 1} 个文件"; changed?.invoke()
        val ticket = next()
        val rate = DemoTransferRate()
        rows[index].kilobytesPerSecond = null
        cancelDownload = download(file, { bytes ->
            if (busy && generation == ticket.first && request == ticket.second) { rows[index].kilobytesPerSecond = rate.sample(bytes); rows[index].received = bytes.coerceIn(0, file.size); changed?.invoke() }
        }, { path, error ->
            if (accept(ticket)) {
                cancelDownload = null
                rows[index].kilobytesPerSecond = null
                if (error != null) {
                    if (stopRequested && error == "同步已取消") {
                        rows[index].status = "同步已停止，断点已保留"
                        end("同步已停止，可点击重新同步"); return@download
                    }
                    if (error == "downloadPositionMismatch" && !stopRequested && !isRecording) {
                        failedThisConnection.add(file.key); rows[index].status = "同步失败，断点已保留"
                        changed?.invoke(); downloadNext(index + 1)
                    } else { rows[index].status = if (isRecording) "录音开始，下载中断" else error; end(if (stopRequested) "同步已暂停，未完成文件不会用于播放" else error) }
                }
                else if (path == null) { rows[index].status = "缺少本地文件"; end("下载未交付完整文件") }
                else { rows[index].localPath = path; rows[index].received = file.size; rows[index].status = "同步完成"; completedRow?.invoke(rows[index].copy()); changed?.invoke(); downloadNext(index + 1) }
            }
        })
    }
    fun phase(file: DemoAudioFile, text: String) {
        if (!busy) return
        val row = rows.firstOrNull { it.file.key == file.key && it.localPath == null } ?: return
        row.status = text; message = text; changed?.invoke()
    }
    fun deleteLocalAudio(file: DemoAudioFile, completion: (String?) -> Unit) {
        if (busy) { completion("文件同步中，请稍候"); return }
        deleteLocal(file) { error ->
            if (error == null) {
                rows = rows.filterNot { it.file.key == file.key }
                changed?.invoke()
            }
            completion(error)
        }
    }
    fun converting(bytes: Long, total: Long) {
        if (!busy) return
        val row = rows.firstOrNull { it.localPath == null && (it.status in listOf("正在同步", "检查断点", "继续下载") || it.status.startsWith("正在生成音频") || it.status.startsWith("重新封装")) } ?: return
        val percent = if (total > 0) (bytes * 100 / total).coerceIn(0, 100) else 0
        val label = if (row.status.startsWith("重新封装")) "重新封装" else "正在生成音频文件"
        row.kilobytesPerSecond = null
        row.status = "$label $percent%"
        message = "下载完成，$label $percent%"; changed?.invoke()
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

/** 按新增原始字节每至少 0.5 秒更新，1 KB = 1024 字节；首次基线排除续传已有字节。 */
class DemoTransferRate {
    private var anchorBytes: Long? = null
    private var anchorTime = 0.0
    private var rate: Double? = null
    fun sample(bytes: Long, now: Double = System.nanoTime() / 1e9): Double? {
        val previous = anchorBytes
        if (previous == null || bytes < previous || now < anchorTime) {
            anchorBytes = bytes; anchorTime = now; rate = null; return null
        }
        val elapsed = now - anchorTime
        if (elapsed >= 0.5) {
            rate = (bytes - previous).toDouble() / elapsed / 1024
            anchorBytes = bytes; anchorTime = now
        }
        return rate
    }
}
