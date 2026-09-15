package cn.wavenote.demo

import org.junit.Assert.*
import org.junit.Test

class DemoFlowTest {
    @Test fun unboundBindsThenConnectsOnlyOnce() {
        val flow = DemoFlow(); val (token, route) = flow.select(false, true)!!
        assertEquals(DemoFlow.Route.BIND, route); assertTrue(flow.busy)
        assertNull(flow.select(false, true)); assertTrue(flow.bound(token, true))
        assertFalse(flow.bound(token, true)); assertFalse(flow.ready); flow.connected(); assertTrue(flow.ready)
    }
    @Test fun boundSkipsBindAndWaitsForReady() {
        val flow = DemoFlow(); val (token, route) = flow.select(true, true)!!
        assertEquals(DemoFlow.Route.CONNECT, route); assertFalse(flow.bound(token, true))
        assertNull(flow.beginSetting()); flow.connected(); assertNotNull(flow.beginSetting())
    }
    @Test fun bindingFailureAllowsRetryWithoutConnection() {
        val flow = DemoFlow(); val token = flow.select(false, true)!!.first
        assertFalse(flow.bound(token, false)); assertFalse(flow.ready); assertNotNull(flow.select(false, true))
    }
    @Test fun expiredDiscoveryDoesNotStart() {
        val flow = DemoFlow(); assertNull(flow.select(false, false)); assertFalse(flow.busy)
    }
    @Test fun disconnectInvalidatesOldBindingAndReadyPage() {
        val flow = DemoFlow(); val token = flow.select(false, true)!!.first
        flow.invalidate(); assertFalse(flow.bound(token, true)); flow.connected()
        val query = flow.beginSetting()!!; flow.invalidate()
        assertFalse(flow.ready); assertFalse(flow.finish(query)); assertNull(flow.beginSetting())
    }
    @Test fun lateSettingCannotFinishNewOperation() {
        val flow = DemoFlow(); flow.connected(); val first = flow.beginSetting()!!
        assertNull(flow.beginSetting()); assertTrue(flow.finish(first)); val second = flow.beginSetting()!!
        assertFalse(flow.finish(first)); assertTrue(flow.busy); assertTrue(flow.finish(second))
    }
    @Test fun ownershipPersistsAndDisconnectKeepsBinding() {
        var disk: Map<String, String> = emptyMap()
        fun store() = DemoOwnershipStore({ disk }, { disk = it })
        assertTrue(store().bind("device-hash", "user-hash"))
        val flow = DemoFlow(); flow.connected(); flow.invalidate()
        assertEquals("user-hash", store().owner("device-hash"))
        assertTrue(store().bind("device-hash", "user-hash"))
        assertTrue(store().unbind("device-hash", "user-hash")); assertNull(store().owner("device-hash"))
    }
    @Test fun otherUserCannotMutateOwnership() {
        var disk = mapOf("device-hash" to "owner-hash")
        val store = DemoOwnershipStore({ disk }, { disk = it })
        assertFalse(store.bind("device-hash", "other-hash")); assertFalse(store.unbind("device-hash", "other-hash"))
        assertEquals("owner-hash", store.owner("device-hash"))
    }
    @Test fun rawGainBoundariesAndMalformedInput() {
        assertEquals(0, DemoFlow.gain("0")); assertEquals(255, DemoFlow.gain("255"))
        listOf("", "-1", "256", "1.5", " ", "１２", "9999999999999999999999999").forEach { assertNull(DemoFlow.gain(it)) }
    }
    @Test fun scanStopDuringBindingKeepsSelection() {
        val flow = DemoFlow(); val token = flow.select(false, true)!!.first
        flow.disconnected(); assertTrue(flow.busy); assertTrue(flow.bound(token, true))
        flow.disconnected(); assertTrue(flow.selecting); flow.connected(); assertTrue(flow.ready)
    }
    @Test fun repeatedDisconnectKeepsSingleGenerationForShutdownResult() {
        val flow = DemoFlow(); flow.connected(); val token = flow.beginSetting()!!
        flow.disconnected(); flow.disconnected(); assertEquals(token + 1, flow.generation)
        assertFalse(flow.ready); assertFalse(flow.finish(token))
    }
    @Test fun unknownAndCustomAutoPowerOffAreNotDefaulted() {
        assertEquals("未知", DemoFlow.minutes(null)); assertEquals("永不", DemoFlow.minutes(0)); assertEquals("17 分钟", DemoFlow.minutes(17))
    }
}

class DemoSettingsTest {
    @Test fun queriesAreSequentialAndDuplicatesDoNotAdvance() {
        val requested = mutableListOf<Int>(); val callbacks = mutableListOf<(Int?) -> Unit>(); var completions = 0
        DemoReadSequence.run<Int, Int>(listOf(102, 105, 106), { true }, { item, done -> requested.add(item); callbacks.add(done) }, { completions++ })
        assertEquals(listOf(102), requested); callbacks[0](null); callbacks[0](null)
        assertEquals(listOf(102, 105), requested); callbacks[1](null); callbacks[2](null); callbacks[2](null)
        assertEquals(listOf(102, 105, 106), requested); assertEquals(1, completions)
    }
    @Test fun queryFailureStopsWithoutSuccess() {
        val requested = mutableListOf<Int>(); var result: Int? = null
        DemoReadSequence.run<Int, Int>(listOf(102, 105), { true }, { item, done -> requested.add(item); done(1103) }, { result = it })
        assertEquals(listOf(102), requested); assertEquals(1103, result)
    }
    @Test fun oldSessionQueryCannotUpdateNewSession() {
        val flow = DemoFlow(); flow.connected(); val token = flow.beginSetting()!!
        var callback: ((Int?) -> Unit)? = null; var finished = false
        DemoReadSequence.run<Int, Int>(listOf(102, 105), { flow.accepts(token) }, { _, done -> callback = done }, { finished = true })
        flow.disconnected(); flow.connected(); callback?.invoke(null); assertFalse(finished)
    }
    @Test fun unknownUsbIsNotOffAndCannotBeEdited() {
        assertEquals("未知", DemoValues.usb(null)); assertFalse(DemoValues.canSetUSB(null))
        assertEquals("已关闭", DemoValues.usb(0)); assertTrue(DemoValues.canSetUSB(0))
        assertEquals("已开启", DemoValues.usb(1)); assertTrue(DemoValues.canSetUSB(1))
    }
    @Test fun readbackFailureAndShutdownUnknownStayExplicit() {
        assertTrue(DemoValues.error(1202).contains("未确认")); assertTrue(DemoValues.error(1203).contains("结果未确认"))
        assertFalse(DemoValues.error(1203).contains("已确认关机"))
    }
}
