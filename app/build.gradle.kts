import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}
fun developmentProperty(name: String): String =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name).orEmpty()
fun buildConfigString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "cn.wavenote.demo"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.wavenote.demo"; minSdk = 29; targetSdk = 36; versionCode = 3; versionName = "0.2.1"
        buildConfigField("String", "DEMO_R202_CLOUD_PRIVATE_KEY_PKCS8_B64", "\"\"")

        buildConfigField("String", "DEMO_R202_USER_PUBLIC_KEY_B64", "\"\"")
        buildConfigField("String", "DEMO_R202_USER_PRIVATE_KEY_PKCS8_B64", "\"\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes {
        getByName("debug") {
            // Development-only migration path for an R202 key pair already pinned by a device.
            // local.properties is ignored by Git. Production credentials must never be packaged this way.
            buildConfigField("String", "DEMO_R202_CLOUD_PRIVATE_KEY_PKCS8_B64", buildConfigString(developmentProperty("demoR202CloudPrivateKeyPkcs8B64")))
            buildConfigField("String", "DEMO_R202_USER_PUBLIC_KEY_B64", buildConfigString(developmentProperty("demoR202UserPublicKeyB64")))
            buildConfigField("String", "DEMO_R202_USER_PRIVATE_KEY_PKCS8_B64", buildConfigString(developmentProperty("demoR202UserPrivateKeyPkcs8B64")))
        }
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    if (providers.gradleProperty("localAar").getOrElse("true") == "true") {
        implementation(files("libs/wavenote-sdk.aar"))
        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    } else implementation("cn.wavenote:wavenote-sdk:${providers.gradleProperty("sdkVersion").getOrElse("0.2.1")}")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
