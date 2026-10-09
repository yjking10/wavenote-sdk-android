package cn.wavenote.demo

import org.junit.Assert.*
import org.junit.Test

class DemoRefreshGateTest {
    @Test fun transferProgressDoesNotRequireRebuildingThePage() {
        val policy = DemoFileTransferRenderPolicy()
        val rendered = listOf("1:100:recording.vrc" to null)
        assertFalse(policy.needsRebuild(rendered, rendered))
    }

    @Test fun completedFileOrAddedRowRequiresRebuildingThePage() {
        val policy = DemoFileTransferRenderPolicy()
        val rendered = listOf("1:100:recording.vrc" to null)
        assertTrue(policy.needsRebuild(rendered, listOf("1:100:recording.vrc" to "/local/recording.ogg")))
        assertTrue(policy.needsRebuild(rendered, rendered + ("2:200:call.vrc" to null)))
    }

    @Test fun burstRefreshesOnceUsingLatestStateAndAllowsNextWindow() {
        val queued = mutableListOf<() -> Unit>()
        var state = 0
        val observed = mutableListOf<Int>()
        val gate = DemoRefreshGate({ queued.add(it) }, { observed.add(state) })
        repeat(1000) { state = it; gate.request() }
        assertEquals(1, queued.size)
        queued.removeAt(0).invoke()
        assertEquals(listOf(999), observed)
        state = 1000; gate.request(); queued.removeAt(0).invoke()
        assertEquals(listOf(999, 1000), observed)
    }
    @Test fun destroyDiscardsPendingAndFutureRefreshes() {
        val queued = mutableListOf<() -> Unit>()
        var calls = 0
        val gate = DemoRefreshGate({ queued.add(it) }, { calls++ })
        gate.request(); gate.close(); queued.removeAt(0).invoke(); gate.request()
        assertEquals(0, calls); assertTrue(queued.isEmpty())
    }
}
