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
    implementation(files("libs/wavenote-sdk.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}
```

Demo 的 [AndroidManifest.xml](app/src/main/AndroidManifest.xml) 可作为权限声明参考；仍须在运行时向用户请求相应权限。

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
                enableAutoReconnect = false,
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
    private fun showError(error: WaveNoteError) = Unit
    private fun updateDisconnectedUI() = Unit
}
```

使用最近一次扫描回调中的 `WaveNoteDiscoveredDevice` 调用 `bind` 或 `connect`。`bind` 会先确认账户归属：设备未绑定时绑定到当前用户，已属于当前用户时成功，属于其他用户时返回错误。绑定成功后仍需显式调用 `connect`。

只有连接状态变为 `READY` 后，才调用设备设置、录音和文件接口。请按 `WaveNoteError.errorCode` 或 `code` 处理错误，不要解析错误文本。需要停止扫描时调用 `stopScanning()`；主动断开时调用 `disconnectDevice()`。

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

Demo 还演示了 `setMicrophoneGain`、`setVibrationGain`、`setAutoPowerOff` 和 `setUSBEnabled`。这些设置均应在设备空闲时调用，并在 Completion 返回成功后更新界面。

## 文件同步与本地音频

文件区提供“使用 Wi-Fi 快传”入口；BLE 同步过程中也可点击。Demo 会先停止当前下载并保留断点，完成热点切换后以 Wi-Fi 从断点继续；退出快传后等待蓝牙恢复。

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
