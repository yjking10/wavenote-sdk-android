package cn.wavenote.demo

/** Presentation only; SDK callbacks remain the authority for OTA results. */
class DemoOTAFlow {
    enum class Outcome { SUCCESS, CANCELLED, FAILED }
    var generation = 0; private set
    var active = false; private set
    var cancelPending = false; private set
    var cancelAcknowledged = false; private set
    var stage: Int? = null; private set
    var percent = 0; private set
    var outcome: Outcome? = null; private set
    var businessReady = false; private set
    val protectsExit get() = active
    companion object { fun validFileSize(size: Long) = size in 1..Int.MAX_VALUE.toLong() }
    fun selectFirmware(): Boolean {
        if (active) return false
        generation++; stage = null; percent = 0; outcome = null; businessReady = false
        cancelPending = false; cancelAcknowledged = false
        return true
    }
    fun begin(): Int? {
        if (active) return null
        generation++; active = true; stage = 0; percent = 0; outcome = null; businessReady = false
        cancelPending = false; cancelAcknowledged = false
        return generation
    }
    fun update(token: Int, stage: Int, percent: Int) {
        if (token != generation || !active || stage !in 0..6) return
        this.stage = stage; this.percent = percent.coerceIn(0, 100)
    }
    fun finish(token: Int, result: Outcome): Boolean {
        if (token != generation || !active) return false
        active = false; outcome = result; cancelPending = false; cancelAcknowledged = false
        stage = when (result) { Outcome.SUCCESS -> 4; Outcome.CANCELLED -> 5; Outcome.FAILED -> 6 }
        if (result == Outcome.SUCCESS) percent = 100
        businessReady = false
        return true
    }
    fun beginCancel(): Int? {
        if (!active || cancelPending || (stage ?: 0) >= 4) return null
        cancelPending = true; cancelAcknowledged = false; return generation
    }
    fun cancelResult(token: Int, accepted: Boolean): Boolean {
        if (token != generation || !active || !cancelPending) return false
        cancelPending = accepted; cancelAcknowledged = accepted; return true
    }
    fun connectionReady(ready: Boolean) { if (!active) businessReady = ready }
    val title: String get() {
        if (active) {
            if (stage == 4) return "固件升级完成，正在结束升级流程"
            if (stage == 5) return "取消已确认，正在结束升级流程"
            if (stage == 6) return "升级失败，正在结束升级流程"
            if (cancelPending) return if (cancelAcknowledged) "等待设备取消结果…" else "正在请求取消…"
            return listOf("准备中", "升级中", "重新连接", "设备重启中")[(stage ?: 0).coerceIn(0, 3)]
        }
        return when (outcome) {
            Outcome.SUCCESS -> if (businessReady) "升级成功，业务连接已恢复" else "升级完成，正在恢复业务连接…"
            Outcome.CANCELLED -> if (businessReady) "已取消，业务连接已恢复" else "已取消，等待业务连接恢复…"
            Outcome.FAILED -> "升级失败"
            null -> "未开始升级"
        }
    }
}
