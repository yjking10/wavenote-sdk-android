# WaveNote Android SDK Demo

本项目展示如何在 Android 应用中集成 WaveNote SDK，完成设备扫描、绑定、连接、录音控制、设备设置和音频文件同步。本文面向应用开发者；服务端鉴权与账户归属由接入方实现。

可运行参考代码：[Kotlin](app/src/main/java/cn/wavenote/demo/KotlinIntegration.kt) · [Java](app/src/main/java/cn/wavenote/demo/JavaIntegration.java) · [Identity Provider](app/src/main/java/cn/wavenote/demo/IdentityProvider.kt)。

## 集成前提

- Android 10（API 29）及以上。
- 将 SDK AAR 放入应用模块的 `libs/` 目录。
- 所有 SDK API 调用、Completion 和 Delegate 回调均在主线程；耗时 UI 外工作请自行切换至工作线程。
- 使用蓝牙前，按下表在 `AndroidManifest.xml` 声明权限，并在需要的系统版本申请运行时权限。

| 系统版本                   | Manifest 权限                                                                          | 运行时授权要求                                                             |
| -------------------------- | -------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Android 12+（API 31+）     | `android.permission.BLUETOOTH_SCAN`、`android.permission.BLUETOOTH_CONNECT`            | 请求 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`，系统将其归入“附近设备”授权。 |
| Android 10–11（API 29–30） | `android.permission.ACCESS_FINE_LOCATION`、`android.permission.ACCESS_COARSE_LOCATION` | 请求定位权限，并开启系统定位服务后才能扫描蓝牙设备。                       |

当前 Demo 不请求 `RECORD_AUDIO` 或存储权限。`BLUETOOTH_SCAN` 使用 `neverForLocation` 标志，表示 SDK 不将扫描结果用于推断位置。使用文件区的 Wi-Fi 快传时，Android 13+ 会请求 `NEARBY_WIFI_DEVICES`，Android 10–12L 会请求精确定位权限。

在应用模块的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(files("libs/wavenote-sdk-0.2.7.aar"))
    implementation(files("libs/wavenote-audio-engine-1.0.2.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    // 本地 AAR 不会自动解析运行依赖；蓝牙 OTA 的权限检查需要 Core
    implementation("androidx.core:core:1.7.0")
    // WaveNote Audio Engine 运行时依赖
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-extractor:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

宿主的 `gradle.properties` 需设置 `android.useAndroidX=true`。Maven 接入 `cn.wavenote:wavenote-sdk:0.2.7` 会自动解析 Core；本地 AAR 按上面的依赖配置。

Demo 的 [AndroidManifest.xml](app/src/main/AndroidManifest.xml) 可作为权限声明参考；仍须在运行时向用户请求相应权限。

## 导出 SDK 生产日志

首页顶部“导出日志”在未连接、连接失败或蓝牙关闭时仍可使用。点击后显示“正在导出…”，生成快照期间禁止重复点击，成功后打开 Android 系统分享菜单；分享取消正常返回，不提示发送成功。

附件仅包含 SDK 生产诊断 JSONL，不包含音频、逐帧控制台日志或设备端日志。Demo 显式开启后记录关键状态、错误码、阶段和耗时，默认最多 2 MiB／7 天，可通过 `configureProductionDiagnostics` 调整；与 `openLog` 独立。导出文件最多保留 24 小时，在启动或后续导出时清理；换用户、退出和清除诊断时提前删除，分享时不要删除附件。

宿主调用 `sdk.exportDiagnostics { file, error -> ... }`，通过只读 ContentProvider 生成 `content://` URI，再使用 `ACTION_SEND`、`ClipData`、`FLAG_GRANT_READ_URI_PERMISSION` 和 `Intent.createChooser` 打开分享。完整实现见 `MainActivity.kt`、`DiagnosticsShareProvider.kt` 及 Manifest 注册；Provider 不导出，只接受临时授权，拒绝目录枚举、路径穿越和写入，不需要存储权限或额外依赖。SDK 不联网上传。

`app/libs/wavenote-sdk-0.2.7.aar` 已更新为包含生产诊断配置接口的本地构建版。

## Audio Engine：本地音频处理

Demo 已引入 `wavenote-audio-engine-1.0.2.aar`，用于处理 `downloadToStorage` 返回的已完成本地音频。它与设备传输解耦，不能播放正在下载、已取消或不完整的文件。

核心能力包括：

- 单文件播放：准备、播放/暂停/停止、跳转、音量（`0f..1f`）、倍速（`0.5f..2f`）和单曲循环；播放器同时只管理一个本地文件。
- `events` 事件流：播放状态、毫秒级位置和时长、播放完成、系统中断、降噪准备/就绪/绕过和不可恢复错误。
- 实时降噪：提供 `OFF`、`LIGHT`、`BALANCED`、`STRONG` 四档；切换是异步的，应根据事件更新 UI。
- 离线降噪：将本地音频渲染为新的 WAV 文件；Demo 将结果先写入缓存文件，再通过 Storage Access Framework 写入用户选择的位置。

以下 Kotlin 示例展示 Demo 使用的播放和实时降噪方式。播放器与 `events` 收集共用受控协程；宿主销毁时应取消该协程并调用 `close()`。不要在主线程执行文件拷贝等阻塞 I/O。

```kotlin
import android.content.Context
import cn.wavenote.audio.player.NoiseSuppressionLevel
import cn.wavenote.audio.player.PlayerEvent
import cn.wavenote.audio.player.WaveNoteAudioPlayer
import cn.wavenote.sdk.WaveNoteLocalAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LocalAudioController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = WaveNoteAudioPlayer(context.applicationContext)

    init {
        scope.launch {
            player.events.collect { event ->
                when (event) {
                    is PlayerEvent.PositionChanged ->
                        renderProgress(event.positionMilliseconds, event.durationMilliseconds)
                    is PlayerEvent.Completed -> showCompleted()
                    is PlayerEvent.NoiseSuppressionBypassed -> showNoiseSuppressionUnavailable()
                    is PlayerEvent.FatalError -> showPlaybackError()
                    else -> Unit
                }
            }
        }
    }

    fun open(audio: WaveNoteLocalAudio) {
        scope.launch {
            runCatching {
                player.prepare(audio.file)
                player.setRate(1.25f)
                player.setNoiseSuppressionLevel(NoiseSuppressionLevel.BALANCED)
                player.play()
            }.onFailure(::showError)
        }
    }

    fun seekTo(milliseconds: Long) {
        scope.launch { player.seek(milliseconds) }
    }

    fun pause() {
        scope.launch { player.pause() }
    }

    fun close() {
        scope.cancel()
        player.close()
    }

    private fun renderProgress(position: Long, duration: Long) = Unit
    private fun showCompleted() = Unit
    private fun showNoiseSuppressionUnavailable() = Unit
    private fun showPlaybackError() = Unit
    private fun showError(error: Throwable) = Unit
}
```

`WaveNoteLocalAudio.file` 仅在 SDK 已成功交付本地文件时使用。离线降噪导出和完整生命周期处理可参考 [DemoNativePlayer.kt](app/src/main/java/cn/wavenote/demo/DemoNativePlayer.kt)。

## 运行 Demo 的开发凭据

此配置仅用于本地运行 Demo 和开发设备联调，**不得用于生产应用**。请勿提交真实私钥或把它们写入日志。

1. 将 [example.properties](example.properties) 复制为 Demo 根目录下的 `local.properties`。如果该文件已经包含 Android SDK 的 `sdk.dir`，请保留该配置。

   ```bash
   cp example.properties local.properties
   ```

2. 在 `local.properties` 中填写 Base64 编码的开发凭据：

   | 键                                | 填写内容                                     |
   | --------------------------------- | -------------------------------------------- |
   | `demoR202CloudPrivateKeyPkcs8B64` | 开发环境云服务私钥（PKCS#8）。               |
   | `demoR202UserPublicKeyB64`        | 当前开发用户的公钥（SPKI）。                 |
   | `demoR202UserPrivateKeyPkcs8B64`  | 与上述公钥配对的当前开发用户私钥（PKCS#8）。 |

`local.properties` 已被 Git 忽略。这些值只会用于 Debug 构建；生产环境应由 `WaveNoteIdentityProvider` 从可信服务或安全存储获取签名与用户密钥材料，绝不能将生产私钥打包进 APK。

## 初始化与连接

SDK 使用当前登录用户的稳定标识和宿主实现的 `WaveNoteIdentityProvider` 配置。应用必须强持有 Provider 和 Delegate；SDK 对 Delegate 使用弱引用。`configure` 不会自动开始扫描或连接。

```kotlin
class NoteManager(
    context: Context,
    private val identityProvider: WaveNoteIdentityProvider
) : WaveNoteSDKDelegate {
    private val sdk = WaveNoteSDK.getInstance(context)

    fun start(userIdentifier: String) {
        sdk.delegate = this
        sdk.configure(
            WaveNoteSDKConfiguration(
                userIdentifier = userIdentifier,
                enableAutoReconnect = true,
                reconnectPolicy = WaveNoteReconnectPolicy.DEFAULT,
                identityProvider = identityProvider
            )
        )
    }

    fun scan() {
        sdk.startScanning()
    }

    override fun didUpdateDiscoveredDevices(
        sdk: WaveNoteSDK,
        devices: List<WaveNoteDiscoveredDevice>
    ) {
        // 在界面中展示 devices；用户选择后调用 selectDevice(device)。
    }

    fun selectDevice(device: WaveNoteDiscoveredDevice) {
        sdk.bind(device) { _, error ->
            if (error == null) sdk.connect(device)
            else showError(error)
        }
    }

    override fun didChangeConnectionState(
        sdk: WaveNoteSDK,
        state: WaveNoteConnectionState,
        device: WaveNoteDevice?
    ) {
        when (state) {
            WaveNoteConnectionState.RECONNECTING -> updateReconnectingUI()
            WaveNoteConnectionState.READY -> refreshDevice()
            WaveNoteConnectionState.FAILED,
            WaveNoteConnectionState.DISCONNECTED -> updateDisconnectedUI()
            else -> Unit
        }
    }

    override fun didReceiveError(sdk: WaveNoteSDK, error: WaveNoteError) {
        showError(error)
    }

    private fun refreshDevice() = Unit
    private fun updateReconnectingUI() = Unit
    private fun showError(error: WaveNoteError) = Unit
    private fun updateDisconnectedUI() = Unit
}
```

使用最近一次扫描回调中的 `WaveNoteDiscoveredDevice` 调用 `bind` 或 `connect`。`bind` 会先确认账户归属：设备未绑定时绑定到当前用户，已属于当前用户时成功，属于其他用户时返回错误。绑定成功后仍需显式调用 `connect`。

只有连接状态变为 `READY` 后，才调用设备设置、录音和文件接口。请按 `WaveNoteError.errorCode` 或 `code` 处理错误，不要解析错误文本。需要停止扫描时调用 `stopScanning()`；主动断开时调用 `disconnectDevice()`。

## 自动重连

将 `enableAutoReconnect` 设为 `true` 后，SDK 会在设备曾成功进入 `READY` 且发生可恢复的异常断连时，按当前用户保存的设备 SN 重新扫描、重新执行身份校验并连接。业务层只需根据连接状态更新 UI，**不要**在 `DISCONNECTED`、`FAILED` 或错误回调中自行循环调用 `connect`。

`reconnectPolicy` 决定每轮重连前的等待时间：`NONE` 不重连；`CONSERVATIVE` 为 30/30 秒（2 次）；`DEFAULT` 为 0/3/10 秒（3 次）；`AGGRESSIVE` 为每 3 秒一次（最多 5 次）。`AGGRESSIVE` 仅在前台执行，应用进入后台会暂停当前等待或重扫，回到前台后再评估是否恢复。每次尝试都会进行最长 8 秒的目标设备扫描。

```kotlin
override fun didChangeConnectionState(
    sdk: WaveNoteSDK,
    state: WaveNoteConnectionState,
    device: WaveNoteDevice?
) {
    when (state) {
        WaveNoteConnectionState.RECONNECTING -> {
            // 告知用户正在恢复连接；无需再次调用 connect。
            updateReconnectingUI()
        }
        WaveNoteConnectionState.READY -> {
            // 连接或重连已完成；仅此时恢复依赖 BLE 的操作入口。
            refreshDevice()
        }
        WaveNoteConnectionState.DISCONNECTED,
        WaveNoteConnectionState.FAILED -> updateDisconnectedUI()
        else -> Unit
    }
}

fun retryTapped() {
    // 仅供用户主动点击“重试”时调用；即使自动重连关闭也可使用。
    sdk.reconnectLastDevice()
}
```

`disconnectDevice()` 会取消自动重连并移除当前用户的上次成功连接记录；用户登出或切换账号时调用 `clearConfiguration()`，同样会取消重连。`reconnectLastDevice()` 仅重建连接，不会重放录音、删除、OTA 等有副作用的业务操作；这些操作应由业务在再次收到 `READY` 后决定是否发起。Wi-Fi 切换、OTA、关机/重启及清空设备等流程期间，SDK 会暂停自动重连以避免冲突。

## 设备设置与录音

设备设置查询和控制都要求连接已就绪。查询成功返回最新 `WaveNoteSettingsSnapshot`；设置接口的 Completion 为 `null` 时，表示设备已确认该操作。

```kotlin
fun refreshDevice(sdk: WaveNoteSDK) {
    sdk.deviceSettings.query(WaveNoteSetting.BATTERY) { snapshot, error ->
        if (error == null) renderBattery(snapshot?.batteryLevel)
    }
}

fun startRecording(sdk: WaveNoteSDK) {
    sdk.recording.start { error ->
        if (error != null) showError(error)
    }
}

fun stopRecording(sdk: WaveNoteSDK) {
    sdk.recording.stop { error ->
        if (error != null) showError(error)
    }
}
```

调用录音控制前先检查 `sdk.recording.snapshot.state`：仅 `STOPPED` 状态可开始，`RECORDING` 或 `PAUSED` 状态可停止。可设置 `sdk.recording.delegate` 接收状态更新；Delegate 同样需要由宿主强持有。

Demo 还演示了 `setMicrophoneGain`、`setVibrationGain`、`setAutoPowerOff`、`setUSBEnabled` 和设备文件 Log 开关。设置页查询 110 后显示状态，调用 `sdk.maintenance.setDeviceLogEnabled` 发送 222 并回读确认；未知状态时禁用开关。设备文件 Log 与 SDK 的 `openLog` 独立。这些设置均应在设备空闲时调用，并在 Completion 返回成功后更新界面。

## 文件同步与本地音频

文件区提供“使用 Wi-Fi 快传”入口；BLE 同步过程中也可点击。Demo 会先停止当前下载并保留断点，完成热点切换后以 Wi-Fi 从断点继续；退出快传后等待蓝牙恢复。热点选择最多等待120秒；用户未在系统弹窗确认时，Demo 不显示错误，等待设备自动关闭热点并恢复蓝牙后继续断点同步。BLE 恢复最多等待60秒，连接失败、蓝牙关闭或超时会结束本轮同步并提示重新扫描；权限、Wi-Fi开关、完整建连期限或TCP连接等真实故障仍正常提示。

设备空闲且连接已就绪后，可按录音模式查询文件。`count` 返回数量，`page` 按页返回文件；使用列表结果创建或直接使用 `WaveNoteFile` 下载。托管下载 `downloadToStorage` 由 SDK 管理当前用户的本地音频，Completion 成功时返回 `WaveNoteLocalAudio`。

```kotlin
class AudioSync(private val sdk: WaveNoteSDK) : WaveNoteFilesDelegate {
    init {
        sdk.files.delegate = this
    }

    fun loadFirstPage() {
        sdk.files.page(WaveNoteRecordMode.NOTE, index = 0) { files, error ->
            if (error == null) renderFiles(files.orEmpty())
        }
    }

    fun download(file: WaveNoteFile) {
        sdk.files.downloadToStorage(file, resume = true) { audio, error ->
            if (error == null && audio != null) play(audio.file)
            else if (error != null) showError(error)
        }
    }

    override fun didUpdate(files: WaveNoteFiles, progress: WaveNoteTransferProgress) {
        renderProgress(progress.receivedBytes, progress.totalBytes, progress.state)
    }

    fun findLocal(serialNumber: String, mode: WaveNoteRecordMode, fileName: String) {
        sdk.files.findLocalAudio(serialNumber, mode, fileName) { audio, error ->
            if (error == null && audio != null) play(audio.file)
        }
    }

    fun removeLocal(serialNumber: String, mode: WaveNoteRecordMode, fileName: String) {
        sdk.files.deleteLocalAudio(serialNumber, mode, fileName) { error ->
            if (error != null) showError(error)
        }
    }

    private fun renderFiles(files: List<WaveNoteFile>) = Unit
    private fun renderProgress(received: Long, total: Long, state: WaveNoteOperationState) = Unit
    private fun play(file: java.io.File) = Unit
    private fun showError(error: WaveNoteError) = Unit
}
```

下载完成以 Completion 成功且返回 `WaveNoteLocalAudio` 为准；进度回调中的文件仅在状态为 `COMPLETED` 时可视为完整文件。`findLocalAudio` 不要求蓝牙连接；无匹配时回调为 `audio == null` 且 `error == null`。`deleteLocalAudio` 只删除 SDK 托管的本地音频，不删除设备文件。

下载、录音和设置操作可能互斥。发起操作后保留返回的 `WaveNoteOperation`；需要由用户取消时调用其 `cancel()`，不要依据不确定结果自动重发可能产生副作用的操作。

## 解绑与退出登录

解绑要求当前设备处于 `READY` 且空闲状态。它会请求 Provider 完成账户归属解除；在 UI 中应先取得用户确认。

```kotlin
fun unbind(sdk: WaveNoteSDK) {
    sdk.unbindCurrentDevice { error ->
        if (error != null) showError(error)
    }
}

fun logout(sdk: WaveNoteSDK) {
    sdk.clearConfiguration()
    // 再取消 Provider 的旧请求，清除当前用户的安全缓存和登录态。
}
```

切换用户、Provider 或登录会话时，先调用 `clearConfiguration()`，再配置新用户。该方法会停止当前 SDK 操作并忽略旧会话的迟到回调。

## 服务端与 `WaveNoteIdentityProvider`

`WaveNoteIdentityProvider` 是应用连接 SDK 与接入方账户服务的适配层。接口由 Android 宿主实现，Provider 从当前登录会话取得凭据并请求服务端；SDK 不接收或持久化登录凭据。

每个方法可在任意线程完成，但**每次调用必须恰好回调一次**。成功时 `error` 为 `null`；失败时返回适当的 `WaveNoteError`。在 Provider 中不要记录登录凭据、设备序列号、用户标识、签名或密钥材料。

| 方法                                                             | 服务端职责                                                                                                                       | 成功结果                                      |
| ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| `checkOwnership(serialNumber, userIdentifier, completion)`       | 校验当前用户与设备的账户归属，不改变绑定状态。                                                                                   | `UNBOUND`、`CURRENT_USER` 或 `ANOTHER_USER`。 |
| `bind(serialNumber, userIdentifier, completion)`                 | 在完成用户授权后，将未绑定设备绑定给当前用户。                                                                                   | `error == null`。                             |
| `unbind(serialNumber, userIdentifier, completion)`               | 在完成用户授权后，解除当前用户与设备的账户归属。                                                                                 | `error == null`。                             |
| `fetchDeviceSignature(serialNumber, userIdentifier, completion)` | 在设备身份校验需要时，向可信服务获取本次连接所需的设备签名。设备公钥已内置在设备中，云端服务持有对应私钥；App 不在本地生成签名。 | `WaveNoteDeviceSignature`。                   |
| `fetchUserKeyPair(userIdentifier, completion)`                   | 获取当前用户业务维护的唯一且稳定的 RSA-2048 用户密钥对，供 SDK 在当前连接中完成用户身份校验。                                    | `WaveNoteUserKeyPair`。                       |

实现要求：

- 每次请求基于当前登录用户授权，服务端不得信任客户端传入的用户标识来替代鉴权。
- `bind` 和 `unbind` 应提供幂等保护，避免网络重试或重复请求产生错误的账户归属。
- `fetchDeviceSignature` 应由 App 通过 Provider 请求业务服务端获取签名；设备签名应针对每次连接和自动重连重新获取。云端私钥不得打包进 APK、写入本地配置或由 App 本地生成签名。
- `fetchUserKeyPair` 返回的是接入方业务维护的用户密钥对：每个用户应有唯一且稳定的密钥对，公钥和私钥必须严格匹配。App 将密钥对交给 SDK，SDK 仅在设备端内存中用于当前连接认证；断连或认证失败后清除当前会话中的私钥，长期保存由宿主的 Android Keystore、可信服务或其他安全存储负责。
- 用户公钥用于发送用户身份材料，用户私钥用于解密设备返回的会话材料。密钥格式为：RSA-2048；公钥使用 X.509 SubjectPublicKeyInfo（SPKI）DER 后 Base64 编码，私钥使用 PKCS#8 DER 后 Base64 编码。
- 不需要 X.509 证书，因此没有证书有效期要求，密钥轮换由接入方业务自行管理。
- 不要把签名、私钥、登录凭据或其他敏感材料写入 APK、日志、代码仓库或 README。

Provider 可参考 [IdentityProvider.kt](app/src/main/java/cn/wavenote/demo/IdentityProvider.kt) 的适配方式。该示例仅用于演示接口调用，接入方应按自己的认证体系实现网络请求、会话失效和安全存储策略。


## 蓝牙固件升级

设备设置 → 蓝牙固件升级 → 选择固件文件 → 开始升级。完整页面及导入代码见 [MainActivity.kt](app/src/main/java/cn/wavenote/demo/MainActivity.kt)。

公开 OTA 入口为 `sdk.bluetoothFirmwareUpdate`，用于杰理蓝牙固件。原生依赖已内置，无需 Flutter 或单独接管杰理连接；开始时复用当前已就绪的 BLE 连接，SDK 处理升级期间的必要断开和回连。乐鑫 OTA 仅内部保留实现，暂不提供公共入口。

### 调用前提与接口

- 所有调用、进度代理和 completion 均在主线程。先完成 SDK 配置、蓝牙授权及连接，等待 `connectionState == WaveNoteConnectionState.READY`，再由用户确认设备和固件后启动。
- 传入可读、非空、大小不超过 `Int.MAX_VALUE` 字节的本地文件；宿主确认固件适用于当前设备，并在升级期间保持文件内容和访问权限稳定。SDK 不下载固件，也不按文件名判断适配型号。
- 系统文件选择器返回 `content://` URI 时，先在后台通过 `ContentResolver` 复制到 App 缓存等本地目录，再将 `File` 传入 SDK；不要将 URI 的 path 直接当本地文件。Demo 已实现此流程，无需新增广泛存储权限。
- 直接 AAR 接入须添加 `androidx.core:core:1.7.0` 或兼容的更高版本，并启用 AndroidX，配置见上文“集成前提”。升级期间保持设备供电及 App 前台运行，并在真实蓝牙设备上验收。

以下接口属于 `sdk.bluetoothFirmwareUpdate`；Java 通过 `sdk.getBluetoothFirmwareUpdate()` 取得管理器：

| Kotlin 接口 | Java 对应接口 | 用途 |
| --- | --- | --- |
| `start(file, completion): WaveNoteOperation` | `start(File, WaveNoteControlCompletion)` | 启动升级；completion 在本次升级结束或请求被拒绝时回调一次，`null` 表示原生确认升级成功 |
| `delegate` | `setDelegate(...)` / `getDelegate()` | 接收 `WaveNoteBluetoothFirmwareUpdateDelegate` 进度；弱引用，宿主必须保持代理存活 |
| `snapshot` | `getSnapshot()` | 读取最近的 `WaveNoteOTAProgress?`；初始为 `null`，终态保留到后续已接受的操作更新 |
| `cancel(completion)` | `cancel(WaveNoteControlCompletion)` | 请求原生取消；以 completion 和后续终态确认结果 |

### 完整 Kotlin 示例

将下面的客户端作为页面或业务控制器的属性持有，覆盖整次升级生命周期；不要只在点击事件中创建临时代理。调用 `start(file)` 时传入已复制到 App 本地目录的固件文件。

```kotlin
import cn.wavenote.sdk.*
import java.io.File

class BluetoothOTAClient(sdk: WaveNoteSDK) : WaveNoteBluetoothFirmwareUpdateDelegate {
    private val updater = sdk.bluetoothFirmwareUpdate

    init {
        updater.delegate = this
    }

    val snapshot: WaveNoteOTAProgress? get() = updater.snapshot

    fun start(file: File): WaveNoteOperation = updater.start(file) { error ->
        when {
            error == null -> println("固件升级成功，业务连接就绪另看 SDK 连接代理")
            error.errorCode == WaveNoteErrorCode.OPERATION_CANCELLED ->
                println("蓝牙固件升级已取消")
            else -> println("OTA 未完成 code=${error.code} operation=${error.operation}")
        }
    }

    fun cancel() {
        if (updater.snapshot?.canCancel != true) {
            println("当前阶段不可取消")
            return
        }
        updater.cancel { error ->
            if (error == null) {
                println("原生已确认取消，等待升级终态回调")
            } else {
                println("取消未确认 code=${error.code} operation=${error.operation}")
            }
        }
    }

    override fun didUpdate(updater: WaveNoteBluetoothFirmwareUpdate,
                           progress: WaveNoteOTAProgress) {
        // 在此更新阶段、进度条和取消按钮；按钮可用性使用 canCancel。
        println("OTA state=${progress.state.value} progress=${progress.progress}% canCancel=${progress.canCancel}")
    }
}
```

宿主持有客户端后调用 `client.start(localFirmwareFile)`；页面重绘可读取 `client.snapshot`，用户点击取消时调用 `client.cancel()`。返回令牌的 `identifier` 与进度的 `operationID` 对应；释放令牌不会取消升级。需要处理取消结果时使用管理器的 `cancel(completion)`，令牌 `cancel()` 也只发起请求，不保证设备退出刷写。

### OTA 状态与耗时日志

Demo 已开启 SDK 调试日志；其他宿主在主线程调用 `sdk.openLog(true)` 后，可在 Logcat 的 `WaveNoteSDK` 标签搜索 `OTA state` 查看状态变化，搜索 `OTA timing` 查看整体耗时。若通过 `WaveNoteLogListener` 接管日志，SDK 只交给该回调，需由宿主自行输出或保存。

格式示例（数值仅为演示）：

```text
OTA timing start operation=<UUID>
OTA state operation=<同一UUID> state=WaveNoteOTAState.PREPARING value=0 progress=0 canCancel=false code=0
OTA state operation=<同一UUID> state=WaveNoteOTAState.UPGRADING value=1 progress=12 canCancel=true code=0
OTA state operation=<同一UUID> state=WaveNoteOTAState.COMPLETED value=4 progress=100 canCancel=false code=0
OTA timing end operation=<同一UUID> result=completed elapsedMs=43210 code=0
```

按 `operation` 关联同次请求；`elapsedMs` 为整数毫秒，上例表示 43.210 秒。`result` 为 `completed`、`failed` 或 `cancelled`，`code` 为 0 或 SDK 错误码。每次请求仅记录一次结束日志，前置拒绝也记录 `failed`，不表示已开始刷写。

`OTA state` 打印 `WaveNoteOTAState` 的名称及下表对应的 `value`。仅状态变化时输出，重复进度不重复打印；回连后重新进入 `PREPARING` 会再次记录。`progress`、`canCancel` 为进入该状态时的值，同状态内的更新继续通过 Delegate 接收。准入前直接拒绝时可直接记录 `FAILED`，本地取消记录 `CANCELLED`；已结束任务的迟到事件不再输出状态日志。

计时使用单调时钟，从调用 `start` 到 SDK 交付最终结果，包含前置检查、认证、传输、升级回连和终态清理；不包含文件选择/导入、宿主 completion 内处理或后续业务连接恢复。调整系统时间不影响 `elapsedMs`，应直接读取此字段。

### 进度字段与状态枚举

| `WaveNoteOTAProgress` 字段 | 含义 |
| --- | --- |
| `operationID: UUID` | 本地操作标识，用于区分任务及其回调 |
| `state: WaveNoteOTAState` | 当前统一阶段，见下表 |
| `progress: Int` | 0…100；初始准备为 0，升级阶段最多 99，只有明确成功才为 100 |
| `canCancel: Boolean` | 当前原生阶段是否允许请求取消 |
| `error: WaveNoteError?` | 当前进度携带的失败或取消原因；也须处理 start 的 completion |
| `nativeErrorCode: Int?` | 原生数值诊断代码；业务判断使用 `error.code` 或 `error.errorCode` |

两端状态数值一致，但不保证每次升级都会经过全部阶段：

| 数值 | `WaveNoteOTAState` | 含义与 UI 处理 |
| --- | --- | --- |
| 0 | `PREPARING` | 准备、认证或 Loader 准备，显示准备状态 |
| 1 | `UPGRADING` | 正在传输或执行固件升级，显示进度及 `canCancel` |
| 2 | `RECONNECTING` | SDK 正在处理升级所需回连，等待状态推进 |
| 3 | `REBOOTING` | 原生明确进入重启阶段时报告，等待结果 |
| 4 | `COMPLETED` | 原生明确确认升级成功，进度为 100 |
| 5 | `CANCELLED` | 本次升级已取消；不表示设备已回滚 |
| 6 | `FAILED` | 本次升级失败，按错误码提示并结束本次操作 |

升级进度非递减；回连后的准备、重启、取消或失败保留已有百分比。写入成功、上传完毕、99%、断连或重启都不能单独作为升级成功依据。原生没有独立重启回调时，SDK 不补造 `REBOOTING` 事件。成功或原生确认取消后，SDK 尝试恢复业务连接；业务可用以 SDK 连接代理报告 `READY` 为准，恢复失败不会改写已确认的 OTA 成功结果。

取消按钮使用 `snapshot?.canCancel == true`；阶段可能在点击时变化，因此仍须处理取消 completion。拒绝取消返回 `OPERATION_BLOCKED`（1106），5 秒未确认返回 `OPERATION_OUTCOME_UNKNOWN`（1203），随后以原任务的真实终态为准。已确认取消时，原始 start completion 会返回 `OPERATION_CANCELLED`（1017）。

### OTA 错误码 1301–1309

业务使用 `WaveNoteError.code` 或 `errorCode` 判断；`operation` 用于诊断定位，不解析错误正文或原生数值来替代稳定错误码。拒绝升级不会自动停止录音、取消传输或关闭设备 Wi-Fi。

| 错误码 | `WaveNoteErrorCode` | 原因 | 处理方式 |
| --- | --- | --- | --- |
| 1301 | `OTA_RECORDING_ACTIVE` | 正在录音或暂停录音 | 先结束录音；暂停仍不可升级 |
| 1302 | `OTA_FILE_TRANSFER_ACTIVE` | BLE/Wi-Fi 文件传输、续传、转换或收尾未完成 | 等待整个任务完成 |
| 1303 | `OTA_LIVE_AUDIO_ACTIVE` | 实时音频运行中或停止中 | 先结束实时流并等待停止确认 |
| 1304 | `OTA_WIFI_ACTIVE` | 设备 Wi-Fi 快传开启、使用、关闭或恢复 BLE 中 | 结束快传，等待业务连接恢复 `READY` |
| 1305 | `OTA_ALREADY_IN_PROGRESS` | 已有 OTA、前置检查或原生 OTA 事务 | 等待当前任务结束，避免重复提交 |
| 1306 | `OTA_USB_ACTIVE` | 已确认 USB 连接或挂载 | 断开 USB，等待设备上报断开状态 |
| 1307 | `OTA_DEVICE_BUSY` | 设置、删除、维护、身份或其他控制事务未完成 | 等待控制事务结束 |
| 1308 | `OTA_DEVICE_STATE_UNAVAILABLE` | 状态查询失败、录音状态未知或 USB 明确上报无效值 | 重新连接并确认设备状态后再由用户重试 |
| 1309 | `OTA_POWER_INSUFFICIENT` | 设备或原生 SDK 明确报告电量/电压不足 | 先充电，再由用户重新发起 |

新 BLE 会话就绪且没有收到 USB 上报时，默认 USB 未连接；有效 `116` 通知或 `102.usb_connect` 更新本会话状态，`usb_state` 只表示功能开关。仅未收到 USB 上报不会触发 1308，录音状态仍须确认停止；状态查询超时会关闭不确定会话。SDK 不盲目重发超时指令，也不自行设定低电量百分比阈值。单纯充电、开启边录边传选项但没有活动流，不单独禁止 OTA。

连接、权限、本地文件、取消及升级阶段失败仍沿用 SDK 的其他错误码。固件正确性、真实刷写、回连、取消及成功后的业务连接恢复须在指定真机单独验收；文档示例和编译通过不代表真机 OTA 已通过。
