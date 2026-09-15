package cn.wavenote.demo

/** Demo 交互状态；SDK 独立负责真实身份和设备校验。 */
class DemoFlow {
    enum class Route { BIND, CONNECT }
    var generation = 0; private set
    var busy = false; private set
    var ready = false; private set
    private var awaitingBind = false
    var selecting = false; private set
    val showsNearby get() = !selecting && !ready
    fun select(bound: Boolean, fresh: Boolean): Pair<Int, Route>? {
        if (busy || ready || !fresh) return null
        generation++; busy = true; selecting = true; awaitingBind = !bound
        return generation to if (bound) Route.CONNECT else Route.BIND
    }
    fun bound(token: Int, success: Boolean): Boolean {
        if (!accepts(token) || !awaitingBind) return false
        awaitingBind = false
        if (!success) { busy = false; selecting = false }
        return success
    }
    fun connected() { selecting = false; ready = true; busy = false; awaitingBind = false }
    fun invalidate() { selecting = false; generation++; ready = false; busy = false; awaitingBind = false }
    // 停止扫描的 disconnected 不是已连接设备断开。
    fun disconnected() { if (!selecting && (ready || busy)) invalidate() }
    fun accepts(token: Int) = generation == token
    fun beginSetting(): Int? { if (!ready || busy) return null; generation++; busy = true; return generation }
    fun finish(token: Int): Boolean { if (!accepts(token) || !ready) return false; busy = false; return true }
    companion object {
        fun gain(text: String): Int? = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 0..255 }
        fun minutes(value: Int?) = when (value) { null -> "未知"; 0 -> "永不"; else -> "$value 分钟" }
    }
}

class DemoOwnershipStore(private val read: () -> Map<String, String>, private val write: (Map<String, String>) -> Unit) {
    fun owner(key: String) = read()[key]
    fun bind(key: String, user: String): Boolean {
        val values = read().toMutableMap()
        if (values[key] != null && values[key] != user) return false
        values[key] = user; write(values); return true
    }
    fun unbind(key: String, user: String): Boolean {
        val values = read().toMutableMap()
        if (values[key] != null && values[key] != user) return false
        values.remove(key); write(values); return true
    }
}

object DemoReadSequence {
    /** 失败停止、会话取消后忽略、重复回调不能推进两次。 */
    fun <Item, Failure> run(items: List<Item>, active: () -> Boolean,
        query: (Item, (Failure?) -> Unit) -> Unit, finished: (Failure?) -> Unit) {
        var index = 0; var waiting = false; var ended = false
        fun next() {
            if (!active() || ended) return
            if (index == items.size) { ended = true; finished(null); return }
            val current = index; waiting = true
            query(items[current]) { error ->
                if (active() && !ended && waiting && current == index) {
                    waiting = false
                    if (error != null) { ended = true; finished(error) } else { index++; next() }
                }
            }
        }
        next()
    }
}
object DemoValues {
    fun usb(raw: Int?) = when (raw) { 1 -> "已开启"; 0 -> "已关闭"; else -> "未知" }
    fun canSetUSB(raw: Int?) = raw == 0 || raw == 1
    fun error(code: Int) = when (code) {
        1016 -> "扫描结果已过期，请重新扫描后选择设备。"
        1001 -> "蓝牙不可用，请检查权限和蓝牙开关。"
        1004 -> "设备已绑定其他账户，无法连接。"
        1203 -> "结果未确认，请检查设备状态；不会自动重试。"
        1102 -> "设备应答超时，连接已关闭；请重新扫描连接。"
        1005, 1011, 1106 -> "设备忙碌，请停止录音或等待当前操作完成后重试。"
        1202 -> "设备拒绝设置或回读不一致，未确认保存成功。"
        else -> "操作未完成（$code），请检查设备状态后重试。"
    }
}
