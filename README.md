# wavenote-sdk-android

WaveNote Android 原生 Demo，演示扫描绑定连接、设备设置、空闲时文件同步、下载进度及原生音频播放。使用 **0.2.0-alpha.1** SDK 候选版本。

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
3. 等待 READY 后查询录音状态。仅明确停止录音时，查询 Note/Call 文件列表并顺序下载；录音中只显示当前文件和估算时长。
4. Delegate 更新逐文件进度，completion 确认下载和本地保存完成后显示播放按钮。原生播放器支持播放、暂停和进度显示。
5. 点击顶部已连接 SN 进入设置；电量等查询顺序执行，设置等待回读确认。增益使用 0–255 原始整数。
6. 断开时停止同步和播放、退出设置并隔离旧回调。断开保留模拟归属；解绑需确认，不清空设备内容。维护操作需确认，结果未确认不能当作执行成功。

接入代码：[Kotlin](app/src/main/java/cn/wavenote/demo/KotlinIntegration.kt) · [Java](app/src/main/java/cn/wavenote/demo/JavaIntegration.java) · [HTTP Provider](app/src/main/java/cn/wavenote/demo/IdentityProvider.kt)。HTTP 示例只请求宿主提供的 HTTPS 服务，用户 Token 由宿主登录系统提供；Demo 界面不会发起真实身份请求。切换身份前取消旧请求并重新配置 SDK，不自动重试绑定或解绑。

SDK 日志默认由 Demo 开启，仅输出脱敏摘要；可在配置处调用 `openLog(false)` 关闭。不要记录凭据、原始身份或音频。模拟归属和音频保存在应用私有目录，卸载应用会清除；解绑保留本地音频。首页只展示当前设备的文件，不自动录音。同步失败后由用户重试。
