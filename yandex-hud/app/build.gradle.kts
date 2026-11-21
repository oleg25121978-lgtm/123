plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.rokidhud.probe"
    compileSdk = 35
    defaultConfig { applicationId = "ru.rokidhud.probe"; minSdk = 31; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
}
kotlin { jvmToolchain(17) }
