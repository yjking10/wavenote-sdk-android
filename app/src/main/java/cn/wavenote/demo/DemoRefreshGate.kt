package cn.wavenote.demo

/** 主线程刷新合并：窗口内只排队一次，执行时读取最新模型，不因持续事件推迟刷新。 */
internal class DemoRefreshGate(
    private val schedule: (() -> Unit) -> Unit,
    private val refresh: () -> Unit
) {
    private var pending = false
    private var closed = false
    fun request() {
        if (closed || pending) return
        pending = true
        schedule {
            pending = false
            if (!closed) refresh()
        }
    }
    fun close() { closed = true }
}

/** 区分文件传输的数值更新与需要重建行的结构变化。 */
internal class DemoFileTransferRenderPolicy {
    fun needsRebuild(rendered: List<Pair<String, String?>>, current: List<Pair<String, String?>>) = rendered != current
}
