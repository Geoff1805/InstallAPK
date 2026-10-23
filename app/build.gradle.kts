import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ktlint)
}

ktlint {
  android.set(true)
  ignoreFailures.set(true)
}

android {
  namespace = "fr.installapk.app"
  compileSdk = 36

  val versionPropsFile = file("${rootDir}/version.properties")
  val versionProps = Properties()
  if (versionPropsFile.exists()) {
    versionProps.load(versionPropsFile.inputStream())
  }
  val appVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull()
    ?: (versionProps.getProperty("versionCode", "1").toIntOrNull() ?: 1)
  val appVersionName = (project.findProperty("appVersionName") as? String)
    ?: versionProps.getProperty("versionName", "1.0.0")

  base {
    archivesName.set("InstallAPK-v$appVersionName")
  }

  val defaultToken = (project.findProperty("DEFAULT_GITHUB_TOKEN") as? String)
    ?: System.getenv("DEFAULT_GITHUB_TOKEN")
    ?: ""

  val sentryDsn = (project.findProperty("SENTRY_DSN") as? String)
    ?: System.getenv("SENTRY_DSN")
  val alertEmail = (project.findProperty("ALERT_EMAIL") as? String)
    ?: System.getenv("ALERT_EMAIL")
    ?: "geoff1805@duck.com"

  defaultConfig {
    applicationId = "fr.installapk.app"
    minSdk = 24
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName

    buildConfigField("String", "DEFAULT_GITHUB_TOKEN", "\"$defaultToken\"")
    buildConfigField("String", "ALERT_EMAIL", "\"$alertEmail\"")
    buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val customKeystore = file("${rootDir}/app/debug.keystore")
      val fallbackKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
      storeFile = if (customKeystore.exists()) customKeystore else fallbackKeystore
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      enableV1Signing = true
      enableV2Signing = true
    }
    getByName("debug") {
      val customKeystore = file("${rootDir}/app/debug.keystore")
      if (customKeystore.exists()) {
        storeFile = customKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
      enableV1Signing = true
      enableV2Signing = true
    }
  }

  buildTypes {
    debug {
      signingConfig = signingConfigs.getByName("debug")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)

  // Compose
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.navigation.compose)

  // Coroutines
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  // Network (HTTP & GitHub API)
  implementation(libs.okhttp)

  // Image Loading (Coil)
  implementation(libs.coil.compose)

  // Observability & Debug Network Inspection (STACK_LIBRARY.md § 2.D)
  implementation(libs.sentry.android)
  debugImplementation(libs.chucker.debug)
  releaseImplementation(libs.chucker.noop)

  // Test
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
