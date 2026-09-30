package cn.wavenote.demo

import android.content.Context
import android.net.Uri
import cn.wavenote.audio.player.NoiseSuppressionLevel
import cn.wavenote.audio.player.PlayerEvent
import cn.wavenote.audio.player.PlaybackState
import cn.wavenote.audio.player.WaveNoteAudioDenoiser
import cn.wavenote.audio.player.WaveNoteAudioPlayer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** WaveNoteAudioEngine 的主线程适配层；播放和离线导出均只接受已完成的本地音频。 */
class DemoNativePlayer(private val context: Context) {
    var changed: (() -> Unit)? = null
    /** 高频进度只更新当前播放器页，避免重建页面。单位毫秒。 */
    var progressChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = WaveNoteAudioPlayer(context.applicationContext)
    private val denoiser = WaveNoteAudioDenoiser(context.applicationContext)
    var path: String? = null; private set
    var message = ""; private set
    var preparing = false; private set
    var exporting = false; private set
    var exportProgress = 0f; private set
    var position = 0L; private set
    var duration = 0L; private set
    var noiseLevel = NoiseSuppressionLevel.BALANCED; private set
    val playing get() = player.state.value == PlaybackState.PLAYING
    val fraction get() = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
    val timeText get() = "${timeText(position)} / ${timeText(duration)}"
    val noiseTitle get() = when (noiseLevel) {
        NoiseSuppressionLevel.OFF -> "关闭"
        NoiseSuppressionLevel.LIGHT -> "轻度"
        NoiseSuppressionLevel.BALANCED -> "均衡"
        NoiseSuppressionLevel.STRONG -> "强力"
    }

    init {
        scope.launch { player.events.collect(::apply) }
    }

    /** 准备音频，成功后由播放器页显式点击开始播放。 */
    fun load(source: String) {
        val input = File(source)
        if (!input.isFile) {
            message = "本地音频文件已不存在，请重新同步。"
            changed?.invoke()
            return
        }
        path = source
        preparing = true
        position = 0
        duration = 0
        message = "正在准备播放…"
        changed?.invoke()
        scope.launch {
            // 必须在同一协程内先停止旧会话，避免旧 stop 与新 prepare 交错。
            runCatching { player.stop() }
            runCatching {
                player.prepare(input)
                player.setNoiseSuppressionLevel(noiseLevel)
            }
                .onSuccess { if (path == source) { preparing = false; message = "准备就绪"; changed?.invoke() } }
                .onFailure { if (path == source) { preparing = false; message = "无法准备此音频，请检查文件完整性或剩余空间。"; changed?.invoke() } }
        }
    }

    fun toggle() {
        if (path == null || preparing || exporting) return
        scope.launch {
            runCatching { if (playing) player.pause() else player.play() }
                .onFailure { message = "无法开始播放，请重新打开该音频。"; changed?.invoke() }
        }
    }

    fun seek(target: Long) {
        if (preparing || duration <= 0) return
        val clamped = target.coerceIn(0, duration)
        scope.launch {
            if (player.seek(clamped)) position = clamped else message = "无法跳转播放位置。"
            changed?.invoke()
        }
    }

    fun skip(seconds: Long) = seek(position + seconds * 1_000)

    fun setNoiseSuppressionLevel(level: NoiseSuppressionLevel) {
        noiseLevel = level
        scope.launch {
            runCatching { player.setNoiseSuppressionLevel(level) }
                .onSuccess { message = "降噪已设为$noiseTitle" }
                .onFailure { message = "无法切换降噪等级。" }
            changed?.invoke()
        }
    }

    /** 将当前选择的降噪等级离线渲染为 WAV，并写入用户选定的系统文档位置。 */
    fun exportDenoised(destination: Uri) {
        val source = path?.let(::File)
        if (source?.isFile != true || exporting) return
        if (noiseLevel == NoiseSuppressionLevel.OFF) {
            message = "请先选择轻度、均衡或强力降噪后再导出。"
            changed?.invoke()
            return
        }
        exporting = true
        exportProgress = 0f
        message = "正在导出降噪音频…"
        changed?.invoke()
        scope.launch {
            val output = File(context.cacheDir, "denoised-${UUID.randomUUID()}.wav")
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    denoiser.denoise(source, output, noiseLevel) { progress ->
                        scope.launch { exportProgress = progress.coerceIn(0f, 1f); progressChanged?.invoke() }
                    }
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination, "w")?.use { sink -> output.inputStream().use { it.copyTo(sink) } }
                        ?: error("无法打开导出位置")
                }
            }
            runCatching { output.delete() }
            exporting = false
            exportProgress = if (result.isSuccess) 1f else 0f
            message = if (result.isSuccess) "降噪音频已导出为 WAV。" else "导出失败，请检查文件完整性和目标位置后重试。"
            changed?.invoke()
        }
    }

    fun stop() = stop(clearPath = true)
    private fun stop(clearPath: Boolean) {
        scope.launch { runCatching { player.stop() } }
        preparing = false
        position = 0
        duration = 0
        if (clearPath) path = null
        progressChanged?.invoke()
    }

    fun dispose() {
        changed = null
        progressChanged = null
        scope.cancel()
        player.close()
    }

    private fun apply(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.StateChanged -> {
                preparing = event.state == PlaybackState.PREPARING
                if (event.state == PlaybackState.COMPLETED) message = "播放完成"
                if (event.state == PlaybackState.FAILED) message = "音频播放失败。"
                changed?.invoke()
            }
            is PlayerEvent.PositionChanged -> {
                position = event.positionMilliseconds.coerceAtLeast(0)
                duration = event.durationMilliseconds.coerceAtLeast(0)
                progressChanged?.invoke()
            }
            is PlayerEvent.Completed -> { message = "播放完成"; changed?.invoke() }
            is PlayerEvent.Interrupted -> { message = "播放已被系统中断"; changed?.invoke() }
            is PlayerEvent.NoiseSuppressionPreparing -> { message = "正在应用$noiseTitle 降噪…"; changed?.invoke() }
            is PlayerEvent.NoiseSuppressionReady -> { message = "降噪已启用：$noiseTitle"; changed?.invoke() }
            is PlayerEvent.NoiseSuppressionBypassed -> { message = "当前音频暂不支持降噪，将按原音播放。"; changed?.invoke() }
            is PlayerEvent.NoiseSuppressionRecovered -> { message = "降噪已恢复：$noiseTitle"; changed?.invoke() }
            is PlayerEvent.FatalError -> { preparing = false; message = "播放器发生错误，请重新打开该音频。"; changed?.invoke() }
            else -> Unit
        }
    }

    private fun timeText(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
