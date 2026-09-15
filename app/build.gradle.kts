plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "cn.wavenote.demo"
    compileSdk = 36
    defaultConfig { applicationId = "cn.wavenote.demo"; minSdk = 29; targetSdk = 36; versionCode = 1; versionName = "0.2.0-alpha.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    if (providers.gradleProperty("localAar").getOrElse("true") == "true") {
        implementation(files("libs/wavenote-sdk.aar"))
        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    } else implementation("cn.wavenote:wavenote-sdk:${providers.gradleProperty("sdkVersion").getOrElse("0.2.0-alpha.1")}")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
