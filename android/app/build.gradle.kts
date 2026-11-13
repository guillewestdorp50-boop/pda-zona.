plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "com.pdazona.app"
 compileSdk = 35
 defaultConfig {
  applicationId = "com.pdazona.app"
  minSdk = 23
  targetSdk = 35
  versionCode = 1
  versionName = "1.0.0"
 }
}
dependencies {
 implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
 implementation("androidx.core:core-ktx:1.15.0")
}
