plugins {
    id("com.android.library")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = AndroidConfig.mangaCoreNamespace

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
        }
    }
}
