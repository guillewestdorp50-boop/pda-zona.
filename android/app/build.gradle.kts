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
  versionCode = 4
  versionName = "1.3.0-immersive-orientation"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions {
  jvmTarget = "17"
 }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
}
