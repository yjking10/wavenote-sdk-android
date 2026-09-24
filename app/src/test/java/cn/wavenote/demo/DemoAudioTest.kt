package cn.wavenote.demo

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DemoAudioTest {
    @Test fun transferRateExcludesResumeOffsetAndThrottlesSamples() {
        val rate = DemoTransferRate()
        assertNull(rate.sample(100_000, 10.0))
        assertNull(rate.sample(100_512, 10.25))
        assertEquals(2.0, rate.sample(101_024, 10.5)!!, 0.001)
        assertEquals(0.0, rate.sample(101_024, 11.0)!!, 0.001)
        assertNull(rate.sample(0, 12.0))
        val row = DemoAudioRow(file)
        row.status = "继续下载"; row.kilobytesPerSecond = 2.0
        assertTrue(row.speedText.contains("KB/s"))
        row.status = "正在生成音频文件 10%"; assertEquals("", row.speedText)
        row.status = "同步完成"; assertEquals("", row.speedText)
    }
    private val stopped = DemoRecordingValue(1, null, null)
    private val active = DemoRecordingValue(2, "1800000000.opus", 1)
    private val file = DemoAudioFile("one.opus", 7, 1)
    private fun configured() = DemoAudioLibrary().apply {
        readRecording = { it(stopped, null) }
        count = { mode, done -> done(if (mode == 1) 1 else 0, null) }
        page = { _, _, done -> done(listOf(file), null) }
    }
    @Test fun managedPathRedactsUserInCompletionLog() {
        val row = DemoAudioRow(DemoAudioFile("test.opus",7,1))
        row.localPath = "/private/wavenote/sensitive-user/device/file/id.ogg"
        assertFalse(row.logJSON.contains("sensitive-user")); assertTrue(row.logJSON.contains("[redacted]"))
        assertTrue(row.localPath!!.contains("sensitive-user"))
    }
    @Test fun stoppedListsBothModesThenSequentialDownloadsWithProgress() {
        val lib = configured(); val calls = mutableListOf<String>(); var complete: ((String?, String?) -> Unit)? = null; var progress: ((Long) -> Unit)? = null
        lib.count = { mode, done -> calls.add("count$mode"); done(1, null) }
        lib.page = { mode, _, done -> calls.add("page$mode"); done(listOf(DemoAudioFile("one.opus", 7, mode)), null) }
        lib.download = { file, update, done -> calls.add("download${file.mode}"); progress = update; complete = done; {} }
        val logs = mutableListOf<DemoAudioRow>(); lib.completedRow = { logs.add(it) }
        assertTrue(lib.start()); assertFalse(lib.start())
        assertEquals(listOf("count1", "page1", "count2", "page2", "download1"), calls)
        assertEquals("已完成同步 0/2 个文件", lib.syncCountText)
        progress!!(3); assertEquals(3L, lib.rows[0].received); assertNull(lib.rows[0].localPath)
        lib.converting(3,7); assertEquals("正在生成音频文件 42%", lib.rows[0].status)
        assertEquals(0,lib.completedCount); assertNull(lib.rows[0].localPath)
        val first = complete!!; first("/one.ogg", null); first("/wrong.ogg", null)
        assertEquals("download2", calls.last()); assertEquals("/one.ogg", lib.rows[0].localPath); assertEquals(1, lib.completedCount)
        complete!!("/two.ogg", null); assertFalse(lib.busy); assertEquals("/two.ogg", lib.rows[1].localPath)
        assertEquals("已完成同步 2/2 个文件", lib.syncCountText)
        assertEquals(2,logs.size)
        val json = org.json.JSONObject(logs[0].logJSON)
        assertEquals(7,json.getInt("received")); assertEquals("同步完成",json.getString("status"))
        assertEquals("/one.ogg",json.getString("localPath")); assertEquals("one.opus",json.getJSONObject("file").getString("name"))
    }
    @Test fun transportPreparationRunsAfterListsAndBeforeFirstDownload() {
        val lib = configured(); val calls = mutableListOf<String>(); var prepared: ((String?) -> Unit)? = null
        lib.count = { mode, done -> calls += "count$mode"; done(if (mode == 1) 1 else 0, null) }
        lib.page = { mode, _, done -> calls += "page$mode"; done(listOf(file), null) }
        lib.prepareDownloads = { done -> calls += "prepare"; prepared = done }
        lib.download = { _, _, _ -> calls += "download"; {} }
        assertTrue(lib.start())
        assertEquals(listOf("count1", "page1", "count2", "prepare"), calls)
        prepared!!(null)
        assertEquals("download", calls.last())
    }
    @Test fun positionFailureSkipsFileUntilReconnect() {
        val lib = configured(); val downloads = mutableListOf<String>()
        lib.count = { mode, done -> done(if (mode == 1) 2 else 0, null) }
        lib.page = { mode, _, done -> done(if (mode == 1) listOf(file, DemoAudioFile("two.opus", 7, 1)) else emptyList(), null) }
        lib.download = { item, _, done ->
            downloads += item.name
            done(if (item.name == "one.opus") null else "/two.ogg", if (item.name == "one.opus") "downloadPositionMismatch" else null)
            val cancel: () -> Unit = {}
            cancel
        }
        assertTrue(lib.start())
        assertEquals(listOf("one.opus", "two.opus"), downloads)
        assertEquals(1, lib.failedCount); assertEquals(1, lib.completedCount)
        assertTrue(lib.message.contains("失败 1"))
        assertTrue(lib.start())
        assertEquals(listOf("one.opus", "two.opus", "two.opus"), downloads)
    }
    @Test fun manualStopShowsSavedCheckpointInsteadOfCancellationError() {
        val lib = configured(); var complete: ((String?, String?) -> Unit)? = null
        lib.download = { _, _, done -> complete = done; {} }
        assertTrue(lib.start())
        lib.stop(); complete!!(null, "同步已取消")
        assertFalse(lib.busy)
        assertEquals("同步已停止，断点已保留", lib.rows[0].status)
        assertEquals("同步已停止，可点击重新同步", lib.message)
    }
    @Test fun recordingPausedAndUnknownNeverList() {
        for (state in listOf(0, 2, 3)) {
            val lib = configured(); lib.readRecording = { it(DemoRecordingValue(state, null, null), null) }; lib.count = { _, _ -> fail("must not list") }
            lib.start(); assertFalse(lib.busy); assertTrue(lib.rows.isEmpty())
        }
    }
    @Test fun recordingBetweenPagesStopsNextRequest() {
        val lib = configured(); var respond: ((List<DemoAudioFile>?, String?) -> Unit)? = null
        lib.count = { _, done -> done(2, null) }; lib.page = { _, _, done -> respond = done }
        lib.start(); lib.observe(active); respond!!(listOf(file), null)
        assertFalse(lib.busy); assertTrue(lib.rows.isEmpty())
    }
    @Test fun recordingCancelsDownloadAndOldCallbacksCannotUpdate() {
        val lib = configured(); var cancelled = 0; var complete: ((String?, String?) -> Unit)? = null; var progress: ((Long) -> Unit)? = null
        lib.download = { _, p, d -> progress = p; complete = d; { cancelled++ } }
        lib.start(); lib.observe(active); assertEquals(1, cancelled)
        complete!!(null, "cancelled"); assertNull(lib.rows[0].localPath)
        lib.invalidate(); progress!!(7); complete!!("/late.ogg", null); assertTrue(lib.rows.isEmpty())
    }
    @Test fun lateStoppedQueryCannotOverwriteRecordingNotification() {
        val lib = configured(); var done: ((DemoRecordingValue?, String?) -> Unit)? = null
        lib.readRecording = { done = it }; lib.count = { _, _ -> fail() }
        lib.start(); lib.observe(active); done!!(stopped, null)
        assertTrue(lib.isRecording); assertFalse(lib.busy)
    }
    @Test fun manualStopWaitsForQueryAndNeverSendsNextPage() {
        val lib = configured(); var done: ((List<DemoAudioFile>?, String?) -> Unit)? = null
        lib.page = { _, _, d -> done = d }; lib.download = { _, _, _ -> fail(); {} }
        lib.start(); lib.stop(); assertTrue(lib.busy)
        done!!(listOf(file), null); assertFalse(lib.busy); assertTrue(lib.rows.isEmpty())
    }
    @Test fun duplicatePagesAndCountChangesStopDownload() {
        for (invalid in listOf(listOf(file, file), emptyList())) {
            val lib = configured(); lib.count = { _, done -> done(2, null) }; lib.page = { _, _, done -> done(invalid, null) }
            lib.download = { _, _, _ -> fail(); {} }; lib.start(); assertFalse(lib.busy)
        }
    }
    @Test fun failuresAndDisconnectIgnoreOldRead() {
        val lib = configured(); var done: ((DemoRecordingValue?, String?) -> Unit)? = null
        lib.readRecording = { done = it }; lib.count = { _, _ -> fail() }
        lib.start(); done!!(null, "timeout"); assertEquals("timeout", lib.message)
        lib.start(); lib.invalidate(); done!!(stopped, null); assertFalse(lib.busy)
    }
    @Test fun emptyModesAndZeroSizeCannotPlay() {
        val lib = configured(); lib.count = { _, done -> done(0, null) }; lib.download = { _, _, _ -> fail(); {} }
        lib.start(); assertEquals("设备暂无录音文件", lib.message)
        lib.count = { mode, done -> done(if (mode == 1) 1 else 0, null) }; lib.page = { _, _, done -> done(listOf(DemoAudioFile("empty", 0, 1)), null) }
        lib.start(); assertNull(lib.rows.first().localPath); assertFalse(lib.busy)
    }
    @Test fun clockUsesTimestampAndPausesWithoutCountingTimeTwice() {
        val clock = DemoRecordingClock()
        clock.update(active, 1800000010.0, 100.0); assertEquals(15L, clock.seconds(105.0)); assertTrue(clock.estimated)
        clock.update(DemoRecordingValue(3, active.name, 1), 1800000015.0, 105.0); assertEquals(15L, clock.seconds(110.0))
        clock.update(active, 1800000020.0, 110.0); assertEquals(20L, clock.seconds(115.0))
        clock.update(DemoRecordingValue(2, "bad.opus", 1), 1800000025.0, 115.0)
        assertFalse(clock.estimated); assertEquals(3L, clock.seconds(118.0)); clock.update(stopped); assertEquals(0L, clock.seconds())
    }



    @Test fun resumePhasesNeverMarkCompletedAndIgnoreDisconnectedState() {
        val lib = configured(); lib.download = { _,_,_ -> {} }; lib.start()
        lib.phase(file,"检查断点"); assertEquals("检查断点",lib.rows[0].status)
        lib.phase(file,"继续下载"); assertEquals("继续下载",lib.rows[0].status)
        lib.phase(file,"重新封装"); lib.converting(7,7)
        assertEquals("重新封装 100%",lib.rows[0].status); assertEquals(0,lib.completedCount)
        lib.invalidate(); lib.phase(file,"继续下载"); assertTrue(lib.rows.isEmpty())
    }

    private fun recoveryOgg(): ByteArray {
        fun le(n: Long, count: Int) = ByteArray(count) { (n ushr (it*8)).toByte() }
        fun page(body: ByteArray, number: Int, flags: Int, granule: Long, laces: ByteArray = byteArrayOf(body.size.toByte())): ByteArray {
            val data = "OggS".toByteArray() + byteArrayOf(0,flags.toByte()) + le(granule,8) + le(1,4) + le(number.toLong(),4) + ByteArray(4) + byteArrayOf(laces.size.toByte()) + laces + body
            return recoveryCRC(data)
        }
        val head = "OpusHead".toByteArray() + byteArrayOf(1,1,0,0,0,0,0,0,0,0,0)
        val tags = "OpusTags".toByteArray() + ByteArray(8)
        val packet = byteArrayOf(0xf8.toByte(),0xff.toByte(),0xfe.toByte())
        return page(head,0,2,0) + page(tags,1,0,0) + page(ByteArray(150) { packet[it%3] },2,4,48000,ByteArray(50){3})
    }
    private fun recoveryCRC(page: ByteArray): ByteArray {
        val bytes = page.copyOf(); repeat(4) { bytes[22+it]=0 }
        var crc = 0
        for (b in bytes) { crc = crc xor ((b.toInt() and 255) shl 24); repeat(8) { crc = if (crc<0) (crc shl 1) xor 0x04c11db7 else crc shl 1 } }
        repeat(4) { bytes[22+it]=(crc ushr (8*it)).toByte() }; return bytes
    }




}
