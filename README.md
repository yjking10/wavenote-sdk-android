# wavenote-sdk-android

WaveNote Android 原生 Demo，演示扫描绑定连接、设备设置、空闲时文件同步、已完成/总文件数、逐文件进度及原生音频播放。使用 **0.2.0-alpha.1** SDK 候选版本。

## 快速集成

Android 10 / API 29+；Kotlin 2.2.20 / Java 17。构建工具链：JDK 17、AGP 8.11.1、Gradle 8.14、Android SDK 36。

本仓库随 Demo 提供编译后的 SDK：`app/libs/wavenote-sdk.aar`，Gradle 默认引用该文件并配置 Kotlin 标准库，无需另行下载 SDK 或运行准备脚本。SDK 源码不包含在 Demo 中；`docs/`、日志和构建结果不提交 Git。

```bash
git clone https://github.com/yjking10/wavenote-sdk-android.git
cd wavenote-sdk-android
```

Android Studio 打开本仓库目录，选择 JDK 17，并用 `ANDROID_HOME` 或本地 `local.properties` 指定 Android SDK。等待 Gradle 同步完成，选择 `app` 和运行设备，点击 Run。首次同步需要下载 Gradle 及构建依赖。

集成到自己的工程时，将 AAR 复制到 `app/libs/wavenote-sdk.aar`，在应用模块的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(files("libs/wavenote-sdk.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}
```

更新 SDK 时替换该 AAR，保持文件名一致并重新同步 Gradle。Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；Release 默认未签名，启用 R8。

Android 12+ 允许附近设备权限；Android 10–11 允许定位并开启系统定位服务。SDK AAR 包含可选 Wi-Fi 声明，Demo 只请求 BLE 所需权限，不使用手机麦克风或广泛外部存储权限。

## 主要调用顺序

1. 主线程配置 SDK，持有 Provider 和 Delegate，关闭自动重连。Demo 默认使用固定演示账户及持久化本地模拟归属；这不代表真实服务端绑定。
2. 用户点击扫描并选择设备；未绑定先绑定再连接，当前演示账户已绑定直接连接。选择后隐藏附近设备。
3. SDK在BLE通知订阅就绪后优先自动同步时间，初始化完成才发布READY，宿主无需调用时间接口。等待 READY 后查询录音状态。仅明确停止录音时，查询 Note/Call 最新文件列表并顺序下载；录音中只显示当前文件和估算时长。Demo 先调用 `files.findLocalAudio` 查询当前用户的本地音频，未命中或大小变化时调用 `files.downloadToStorage`。SDK 在下载前持久化未完成任务，按用户、设备 SN、模式、文件名和原始大小核对任务。重连后复用匹配任务的原目标，传入 `resume=true`，由 SDK 验证断点；列表中已不存在或大小变化的文件不续传旧任务。
4. 页面显示“检查断点 / 继续下载 / 重新封装”。原始下载已确认完成时，SDK只重试封装。Delegate 更新下载及封装进度，同时展示已完成同步文件数/总文件数；封装100%仍需等待 SDK 完成索引保存并返回 completion，才显示播放按钮。完成后移除未完成任务索引。原生播放器支持播放、暂停和进度显示。文件 position 不连续时，SDK 确认 306 停止后仅将该文件标为失败；Demo 显示成功/失败/总数并继续后续文件，同一连接不重试失败项，重连后再核对断点并显式续传。306 未确认则停止本轮并断开连接。
5. 点击顶部已连接 SN 进入设置；电量等查询顺序执行，设置等待回读确认。增益使用 0–255 原始整数。
6. 断开时停止同步和播放、退出设置并隔离旧回调。断开保留模拟归属；解绑需确认，不清空设备内容。维护操作需确认，结果未确认不能当作执行成功。

接入代码：[Kotlin](app/src/main/java/cn/wavenote/demo/KotlinIntegration.kt) · [Java](app/src/main/java/cn/wavenote/demo/JavaIntegration.java) · [HTTP Provider](app/src/main/java/cn/wavenote/demo/IdentityProvider.kt)。HTTP Provider 在每次调用时从宿主登录会话取得 Token 快照，SDK 配置和接口不接收凭据；Demo 界面不会发起真实身份请求。退出登录先调用 `clearConfiguration()`，再取消旧请求、删除用户密钥缓存和登录态；不自动重试绑定或解绑。

SDK 日志默认由 Demo 调用 `sdk.openLog(true)` 开启，Debug / Release 均输出脱敏摘要到 Logcat，统一标签为 `WaveNoteSDK`；Demo 自身的文件完成和 Ogg 封装耗时日志使用 `WaveNoteDemo`。可用以下命令只查看相关日志：

```bash
adb logcat -s WaveNoteSDK:V WaveNoteDemo:V '*:S'
```

需要关闭 SDK 日志时调用 `sdk.openLog(false)`。不要记录登录凭据、设备签名、用户密钥、原始身份或音频。模拟归属和音频保存在应用私有目录，卸载应用会清除；解绑保留本地音频。首页只展示当前设备的文件，不自动录音。同步失败后保留任务和原始文件，由用户重连后继续；同一会话不会重复续传。元数据缺失或损坏时明确报错，不自动覆盖或丢弃原文件。新目录为 `wavenote/安全编码的userIdentifier/SHA256(SN)/SHA256("mode:文件名")/设备文件主名.ogg`，由 SDK 管理。旧 Demo 的 Recordings/recordings 目录原样保留，不自动迁移或认领。文件匹配不包含设备端内容哈希，不能识别同名同大小的内容替换。

文件同步成功并保存完成索引后，Demo输出一条 `[FileCompleted]` JSON日志，包含完整DemoAudioRow字段（file.name/size/mode/key、received、status、localPath）。缓存复用成功也会输出；重复或旧completion不重复记录。日志只包含文件元信息，localPath 的用户目录段替换为 `[redacted]`，不包含音频字节、原始 SN 或凭据。

如果 APP 在 SDK 写出正式 Ogg 后、持久化完成索引前退出，下次查询或托管下载时，SDK 会校验该 Ogg的页CRC、序号、流标识、Opus头、时长、EOS和对应原始字节数；通过后原子补写完成索引，复用原文件，不重新下载。校验失败保留文件和任务并报错；补写索引失败也保留任务，供下次重试。此检查针对SDK生成的Ogg结构，不等同于完整音频解码或设备内容哈希验证。

APP 重启后可直接调用 `files.findLocalAudio(serialNumber, mode, fileName, completion)`，无需 BLE 连接。只查询当前配置用户；无匹配返回空结果，损坏返回错误。同名文件大小变化时先清理旧内容，再以同一目标名重新下载；新下载期间不保留旧内容。原 `download` 指定路径接口仍可使用，但不会纳入托管查询。
# R202 development authentication

The Demo contains a development-only `DEV_CLOUD_PRIVATE_KEY_PKCS8_B64` for the matching development firmware. It exists solely to exercise 1001 in local integration. Production apps must obtain the SN signature and the account key pair from an authenticated cloud service; never ship a production cloud private key or log private-key material.

If an R202 development device was previously pinned to an existing user key pair, put that pair only in the ignored `android/Demo/local.properties` before building the Demo:

```properties
demoR202UserPublicKeyB64=…
demoR202UserPrivateKeyPkcs8B64=…
```

This is a temporary local migration path: it compiles the development key into the local **Debug** APK and must never be used for production credentials. Release builds always omit these values. Omit both properties to use the Demo's in-memory per-account development key pair; the Demo never persists the private key.
