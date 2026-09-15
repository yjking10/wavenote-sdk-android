package cn.wavenote.demo

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DemoAudioTest {
    private val stopped = DemoRecordingValue(1, null, null)
    private val active = DemoRecordingValue(2, "1800000000.opus", 1)
    private val file = DemoAudioFile("one.opus", 7, 1)
    private fun configured() = DemoAudioLibrary().apply {
        readRecording = { it(stopped, null) }
        count = { mode, done -> done(if (mode == 1) 1 else 0, null) }
        page = { _, _, done -> done(listOf(file), null) }
    }
    @Test fun stoppedListsBothModesThenSequentialDownloadsWithProgress() {
        val lib = configured(); val calls = mutableListOf<String>(); var complete: ((String?, String?) -> Unit)? = null; var progress: ((Long) -> Unit)? = null
        lib.count = { mode, done -> calls.add("count$mode"); done(1, null) }
        lib.page = { mode, _, done -> calls.add("page$mode"); done(listOf(DemoAudioFile("one.opus", 7, mode)), null) }
        lib.download = { file, update, done -> calls.add("download${file.mode}"); progress = update; complete = done; {} }
        assertTrue(lib.start()); assertFalse(lib.start())
        assertEquals(listOf("count1", "page1", "count2", "page2", "download1"), calls)
        progress!!(3); assertEquals(3L, lib.rows[0].received); assertNull(lib.rows[0].localPath)
        val first = complete!!; first("/one.ogg", null); first("/wrong.ogg", null)
        assertEquals("download2", calls.last()); assertEquals("/one.ogg", lib.rows[0].localPath)
        complete!!("/two.ogg", null); assertFalse(lib.busy); assertEquals("/two.ogg", lib.rows[1].localPath)
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
    @Test fun storeRequiresCommitAndSeparatesDeviceModeAndSize() {
        val root = Files.createTempDirectory("demo-audio").toFile()
        try {
            val store = DemoAudioStore(root); val first = store.prepare("fake-device-a", file)
            first.first.writeBytes(byteArrayOf(1,2,3)); assertFalse(store.prepare("fake-device-a", file).second)
            store.commit(first.first, "fake-device-a", file); assertTrue(store.prepare("fake-device-a", file).second)
            assertFalse(store.prepare("fake-device-b", file).second)
            assertFalse(store.prepare("fake-device-a", file.copy(size = 8)).second)
            assertFalse(store.prepare("fake-device-a", file.copy(mode = 2)).second)
            first.first.delete(); assertFalse(store.prepare("fake-device-a", file).second)
        } finally { root.deleteRecursively() }
    }
}
