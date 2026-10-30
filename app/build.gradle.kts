import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

/** Signing material comes from an uncommitted keystore.properties or from CI environment variables. */
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String? = signingProps.getProperty(key) ?: providers.environmentVariable(key).orNull

val appVersionName: String = providers.gradleProperty("metropulse.versionName").get().removePrefix("v")

/** 1.2.3 -> 10203. Keeps versionCode monotonic as long as minor and patch stay below 100. */
val appVersionCode: Int = appVersionName.substringBefore('-').split('.').map { it.toInt() }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

android {
    namespace = "io.github.arnavdugad.metropulse"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.arnavdugad.metropulse"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "DATA_MANIFEST_URL", "\"${providers.gradleProperty("metropulse.dataManifestUrl").get()}\"")
        // The MTA's terms forbid apps that fetch its realtime feeds directly; enable only with its permission.
        buildConfigField("boolean", "MTA_REALTIME", providers.gradleProperty("metropulse.mtaRealtime").orElse("false").get())
        // Delhi's live bus positions need a key from otd.delhi.gov.in, kept out of the repository
        // (CI secret OTD_DELHI_KEY). It ships inside the APK; without one, Delhi has no live buses.
        val otdKey = secret("OTD_DELHI_KEY") ?: providers.gradleProperty("metropulse.otdDelhiKey").orNull
        buildConfigField("String", "OTD_DELHI_KEY", "\"${otdKey.orEmpty().filter { it.isLetterOrDigit() }}\"")
    }

    signingConfigs {
        val storeFile = secret("METROPULSE_STORE_FILE")
        if (storeFile != null && file(storeFile).exists()) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = secret("METROPULSE_STORE_PASSWORD")
                keyAlias = secret("METROPULSE_KEY_ALIAS")
                keyPassword = secret("METROPULSE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Debug builds are signed with the debug key, so they can never install a release APK.
            // The updater is therefore off unless a local test feed is supplied:
            //   gradlew assembleDebug -Pmetropulse.debugUpdateFeedUrl=http://127.0.0.1:8765/latest.json
            val debugFeed = providers.gradleProperty("metropulse.debugUpdateFeedUrl").orNull
            buildConfigField("String", "UPDATE_FEED_URL", "\"${debugFeed.orEmpty()}\"")
            buildConfigField("boolean", "UPDATES_ENABLED", "${debugFeed != null}")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Phones are ARM; MapLibre's x86 libraries only serve emulators and would double the APK.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("String", "UPDATE_FEED_URL", "\"${providers.gradleProperty("metropulse.updateFeedUrl").get()}\"")
            buildConfigField("boolean", "UPDATES_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        // Compressed native libraries: a much smaller download (the APK is fetched from GitHub),
        // extracted once at install.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "META-INF/*.version")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

baselineProfile {
    // Checked in and regenerated deliberately with :app:generateBaselineProfile.
    saveInSrc = true
    automaticGenerationDuringBuild = false
}

// The profile generator installs a release-like build; without the release keystore it is signed
// with the debug key so it can be installed on a test device.
android.buildTypes.configureEach {
    if (name == "nonMinifiedRelease" || name == "benchmarkRelease") {
        signingConfig = android.signingConfigs.findByName("release") ?: android.signingConfigs.getByName("debug")
    }
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:map"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidsvg)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
