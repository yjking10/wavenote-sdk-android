package cn.wavenote.demo

import org.junit.Assert.*
import org.junit.Test

class DemoOTAFlowTest {
    @Test fun terminalProgressProtectsExitUntilCompletion() {
        val flow = DemoOTAFlow(); val token = flow.begin()!!
        assertTrue(flow.protectsExit); assertNull(flow.begin())
        flow.update(token, 4, 100)
        assertTrue(flow.protectsExit); assertNull(flow.outcome)
        assertTrue(flow.finish(token, DemoOTAFlow.Outcome.SUCCESS)); assertFalse(flow.protectsExit)
    }
    @Test fun newFirmwareClearsPreviousResultAndIgnoresLateProgress() {
        val flow = DemoOTAFlow(); val old = flow.begin()!!
        flow.finish(old, DemoOTAFlow.Outcome.SUCCESS); flow.connectionReady(true)
        assertTrue(flow.selectFirmware()); flow.update(old, 4, 100)
        assertEquals(0, flow.percent); assertNull(flow.stage); assertNull(flow.outcome)
        assertFalse(flow.businessReady); assertEquals("未开始升级", flow.title)
    }
    @Test fun cancelAcknowledgementIsNotTheCancelledState() {
        val flow = DemoOTAFlow(); val token = flow.begin()!!
        assertEquals(token, flow.beginCancel()); assertNull(flow.beginCancel())
        assertTrue(flow.cancelResult(token, true)); assertTrue(flow.cancelPending)
        assertTrue(flow.active); assertNull(flow.outcome); assertNull(flow.beginCancel())
        flow.update(token, 5, 42); assertTrue(flow.protectsExit)
        flow.finish(token, DemoOTAFlow.Outcome.CANCELLED)
        assertFalse(flow.cancelPending); assertEquals(DemoOTAFlow.Outcome.CANCELLED, flow.outcome)
    }
    @Test fun rejectedCancelCanRetryAndLateAcknowledgementCannotOverwriteSuccess() {
        val flow = DemoOTAFlow(); val token = flow.begin()!!
        flow.beginCancel(); flow.cancelResult(token, false)
        assertFalse(flow.cancelPending); assertEquals(token, flow.beginCancel())
        flow.finish(token, DemoOTAFlow.Outcome.SUCCESS)
        assertFalse(flow.cancelResult(token, true)); assertEquals(DemoOTAFlow.Outcome.SUCCESS, flow.outcome)
    }
    @Test fun connectionRecoveryIsSeparateFromUpgradeSuccess() {
        val flow = DemoOTAFlow(); val token = flow.begin()!!
        flow.connectionReady(true); assertFalse(flow.businessReady)
        flow.finish(token, DemoOTAFlow.Outcome.SUCCESS); assertTrue(flow.title.contains("正在恢复"))
        flow.connectionReady(true); assertTrue(flow.title.contains("已恢复"))
        flow.connectionReady(false); assertTrue(flow.title.contains("正在恢复"))
    }
    @Test fun oldCompletionAndProgressCannotChangeANewUpgrade() {
        val flow = DemoOTAFlow(); val first = flow.begin()!!
        flow.finish(first, DemoOTAFlow.Outcome.FAILED); val second = flow.begin()!!
        assertFalse(flow.finish(first, DemoOTAFlow.Outcome.SUCCESS)); flow.update(first, 4, 100)
        assertTrue(flow.active); assertEquals(0, flow.percent)
        flow.update(second, 1, 99); assertEquals(99, flow.percent); assertTrue(flow.protectsExit)
    }
    @Test fun fileBoundariesAndImportDuringUpgrade() {
        assertFalse(DemoOTAFlow.validFileSize(0)); assertFalse(DemoOTAFlow.validFileSize(-1))
        assertTrue(DemoOTAFlow.validFileSize(1)); assertTrue(DemoOTAFlow.validFileSize(Int.MAX_VALUE.toLong()))
        assertFalse(DemoOTAFlow.validFileSize(Int.MAX_VALUE.toLong() + 1))
        val flow = DemoOTAFlow(); flow.begin(); assertFalse(flow.selectFirmware())
    }
}
