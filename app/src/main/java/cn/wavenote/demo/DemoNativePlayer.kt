package cn.wavenote.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/** 原生异步播放器；失去音频焦点或拔出耳机时停止，不自动恢复发声。 */
class DemoNativePlayer(private val context: Context) {
    var changed: (() -> Unit)? = null
    /** 高频进度只更新现有控件，不重建列表。单位毫秒。 */
    var progressChanged: (() -> Unit)? = null
    var position = 0; private set
    var duration = 0; private set
    private var completed = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val fraction get() = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
    val timeText get() = "${DemoRecordingClock.text(position.toLong() / 1000)} / ${DemoRecordingClock.text(duration.toLong() / 1000)}"
    private val tick = object : Runnable {
        override fun run() { refreshProgress(); if (playing) handler.postDelayed(this, 250) }
    }
    private fun refreshProgress() {
        if (!preparing && player != null) {
            position = if (completed) duration else runCatching { player!!.currentPosition.coerceIn(0, duration) }.getOrDefault(position)
        }
        progressChanged?.invoke()
    }
    private fun trackProgress() { handler.removeCallbacks(tick); refreshProgress(); if (playing) handler.postDelayed(tick, 250) }
    var path: String? = null; private set
    var message = ""; private set
    var preparing = false; private set
    private var player: MediaPlayer? = null
    private var generation = 0
    val playing get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) stop() }.build()
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { stop() } }
    init { context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
    fun toggle(source: String) {
        if (path == source && player != null && !preparing) {
            runCatching {
                if (playing) { player?.pause(); message = "播放已暂停" }
                else if (manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    if (completed) player?.seekTo(0)
                    completed = false; player?.start(); message = "正在播放"
                } else message = "无法取得音频焦点，请稍后播放"
            }
                .onFailure { fail() }; trackProgress(); changed?.invoke(); return
        }
        stop(); path = source; preparing = true; message = "正在准备播放…"; changed?.invoke()
        val ticket = generation
        try {
            val media = MediaPlayer(); player = media
            media.setAudioAttributes(attributes)
            media.setOnPreparedListener {
                if (generation == ticket && player === it) {
                    preparing = false; duration = it.duration.coerceAtLeast(0)
                    if (manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        runCatching { it.start(); message = "正在播放"; trackProgress() }.onFailure { fail() }
                    } else { message = "无法取得音频焦点，请稍后播放" }
                    changed?.invoke()
                }
            }
            media.setOnCompletionListener { if (generation == ticket && player === it) { completed = true; handler.removeCallbacks(tick); refreshProgress(); message = "播放完成"; manager.abandonAudioFocusRequest(focus); changed?.invoke() } }
            media.setOnErrorListener { _, _, _ -> if (generation == ticket) fail(); true }
            media.setDataSource(source); media.prepareAsync()
        } catch (_: Exception) { fail() }
    }
    private fun fail() { stop(); message = "无法播放此文件，请检查文件完整性或系统音频支持"; changed?.invoke() }
    fun stop() { handler.removeCallbacks(tick); position = 0; duration = 0; completed = false; generation++; player?.release(); player = null; preparing = false; path = null; message = ""; manager.abandonAudioFocusRequest(focus); changed?.invoke() }
    fun dispose() { changed = null; progressChanged = null; stop(); context.unregisterReceiver(receiver) }
}
