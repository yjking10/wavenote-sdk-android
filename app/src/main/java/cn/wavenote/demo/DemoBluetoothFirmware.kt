package cn.wavenote.demo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import cn.wavenote.sdk.*
import java.io.File
import java.util.UUID

/** Owned by DemoController, so navigation and Activity recreation do not own the OTA callbacks. */
class DemoBluetoothFirmware(context: Context, private val sdk: WaveNoteSDK, private val changed: () -> Unit) : WaveNoteBluetoothFirmwareUpdateDelegate {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val flow = DemoOTAFlow()
    var file: File? = null; private set
    var originalName = ""; private set
    var fileBytes = 0L; private set
    var importing = false; private set
    var importMessage = ""; private set
    var message = "请选择适用于当前设备的本地蓝牙固件。"; private set
    private var operation: WaveNoteOperation? = null
    private var operationID: UUID? = null
    private var targetSerial: String? = null
    private var targetText = ""
    private var versionQuery = 0
    private var versionRequested = false
    private var disposed = false
    val hasSession get() = file != null || importing || flow.stage != null
    val canChoose get() = !flow.active && !importing
    val canStart get() = canChoose && file != null && sdk.connectionState == WaveNoteConnectionState.READY
    val canCancel get() = flow.active && !flow.cancelPending && sdk.bluetoothFirmwareUpdate.snapshot?.let {
        it.operationID == operationID && it.canCancel && it.state.value < 4
    } == true
    init { sdk.bluetoothFirmwareUpdate.delegate = this }

    fun deviceText(): String = if ((flow.active || flow.outcome != null) && targetText.isNotEmpty()) targetText else currentDeviceText()
    fun currentDeviceText(): String = sdk.connectedDevice?.let {
        "目标设备：${it.name ?: "Note"}\nSN：${it.serialNumber}\n当前上报版本：${sdk.deviceSettings.snapshot.firmwareVersion ?: "未知"}"
    } ?: "目标设备尚未连接"

    fun start(expectedGeneration: Int, expectedSerial: String) {
        val selected = file ?: return
        if (!canStart || flow.generation != expectedGeneration || sdk.connectedDevice?.serialNumber != expectedSerial ||
            !selected.isFile || !DemoOTAFlow.validFileSize(selected.length())) {
            message = "设备、连接或固件已变化，请重新核对后开始升级。"; changed(); return
        }
        val before = currentDeviceText().replace("当前上报版本：", "升级前上报版本：")
        val ticket = flow.begin() ?: return
        targetText = before
        targetSerial = expectedSerial; operationID = null; versionRequested = false; versionQuery++
        importMessage = ""; message = "正在准备升级…"; changed()
        val token = sdk.bluetoothFirmwareUpdate.start(selected) { error ->
            if (error != null) android.util.Log.w("WaveNoteOTA", "OTA failed code=${error.code} operation=${error.operation}")
            val result = when (error?.errorCode) {
                null -> DemoOTAFlow.Outcome.SUCCESS
                WaveNoteErrorCode.OPERATION_CANCELLED -> DemoOTAFlow.Outcome.CANCELLED
                else -> DemoOTAFlow.Outcome.FAILED
            }
            if (flow.finish(ticket, result)) {
                operation = null
                message = when (result) {
                    DemoOTAFlow.Outcome.SUCCESS -> "固件升级已完成，正在恢复目标设备的业务连接。"
                    DemoOTAFlow.Outcome.CANCELLED -> "设备已确认取消，等待业务连接恢复。"
                    DemoOTAFlow.Outcome.FAILED -> DemoValues.error(error!!.code) + "（${error.code}）"
                }
                changed(); main.post { connectionChanged() }
            }
        }
        if (flow.generation == ticket && flow.active) { operation = token; operationID = token.identifier }
        changed()
    }

    fun cancel() {
        if (!canCancel) return
        val ticket = flow.beginCancel() ?: return
        message = "正在请求取消，请等待设备确认。"; changed()
        sdk.bluetoothFirmwareUpdate.cancel { error ->
            if (flow.cancelResult(ticket, error == null)) {
                message = error?.let { "取消未确认：${it.code} · ${it.operation}，以升级状态为准。" }
                    ?: "取消请求已确认，正在等待最终升级状态。"
                changed()
            }
        }
    }

    override fun didUpdate(updater: WaveNoteBluetoothFirmwareUpdate, progress: WaveNoteOTAProgress) {
        if (flow.active && operationID == progress.operationID) {
            flow.update(flow.generation, progress.state.value, progress.progress); changed()
        }
    }

    fun connectionChanged() {
        if (disposed || flow.active || flow.outcome == null) return
        val previousReady = flow.businessReady; val previousMessage = message
        val ready = sdk.connectionState == WaveNoteConnectionState.READY && sdk.connectedDevice?.serialNumber == targetSerial
        if (!ready && flow.businessReady) { versionQuery++; versionRequested = false }
        flow.connectionReady(ready)
        if (!ready && flow.outcome == DemoOTAFlow.Outcome.SUCCESS) message = "固件升级已完成，正在恢复目标设备的业务连接。"
        if (flow.outcome == DemoOTAFlow.Outcome.CANCELLED) message = if (ready) "已取消升级，业务连接已恢复。" else "设备已确认取消，等待业务连接恢复。"
        val readVersion = ready && flow.outcome == DemoOTAFlow.Outcome.SUCCESS && !versionRequested
        if (readVersion) {
            versionRequested = true
            message = "业务连接已恢复，正在读取设备上报版本…"
        }
        if (previousReady != ready || previousMessage != message) changed()
        if (readVersion) {
            val ticket = flow.generation; val query = ++versionQuery
            sdk.deviceSettings.query(WaveNoteSetting.HARDWARE) { value, error ->
                if (!disposed && ticket == flow.generation && query == versionQuery &&
                    sdk.connectionState == WaveNoteConnectionState.READY && sdk.connectedDevice?.serialNumber == targetSerial) {
                    message = if (error == null) "业务连接已恢复，设备上报版本：${value?.firmwareVersion ?: "未知"}。"
                        else "业务连接已恢复，版本读取未完成，可在设备设置中刷新。"
                    changed()
                }
            }
        }
    }

    fun importFile(uri: Uri) {
        if (!canChoose) return
        importing = true; importMessage = "正在导入固件…"; changed()
        val destination = File(context.cacheDir, "bluetooth-firmware-${UUID.randomUUID()}.ufw")
        Thread({
            var name = "未命名固件"
            val copied = try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)?.takeIf(String::isNotBlank) ?: name
                }
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192); var size = 0L
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            size += count
                            if (size > Int.MAX_VALUE) throw java.io.IOException()
                            output.write(buffer, 0, count)
                        }
                    }
                }
                DemoOTAFlow.validFileSize(destination.length())
            } catch (_: Exception) { false }
            main.post {
                if (disposed) { destination.delete(); return@post }
                importing = false
                if (copied) {
                    file?.delete(); file = destination; originalName = name; fileBytes = destination.length()
                    flow.selectFirmware(); operationID = null; targetText = ""; targetSerial = null; versionQuery++; versionRequested = false
                    message = "固件已导入，请核对设备和文件后开始升级。"; importMessage = ""
                } else {
                    destination.delete()
                    importMessage = "导入失败，文件为空、过大或无法读取，请重新选择。" +
                        if (file != null) "仍保留上一份固件：$originalName。" else "尚未选择固件。"
                }
                changed()
            }
        }, "WaveNoteDemo.firmware").start()
    }

    fun dispose() { disposed = true; versionQuery++; sdk.bluetoothFirmwareUpdate.delegate = null }
}
