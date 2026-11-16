import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val versionProperties =
    Properties().apply {
        rootProject.file("version.properties").inputStream().use(::load)
    }
val cloudSyncVersionName: String = versionProperties.getProperty("VERSION_NAME")

fun semverCode(name: String): Int {
    val parts =
        name.split(".").map { token ->
            token.takeWhile { it.isDigit() }.toIntOrNull()
                ?: error("VERSION_NAME must be MAJOR.MINOR.PATCH, got '$name'")
        }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    require(major in 0..21) { "major version must be 0..21 so versionCode stays inside Android's limit" }
    require(minor in 0..999) { "minor version must be 0..999" }
    require(patch in 0..99_999) { "patch version must be 0..99999" }
    return major * 100_000_000 + minor * 100_000 + patch
}

android {
    namespace = "dev.cloudsync.app"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "dev.cloudsync.app"
        minSdk = 26
        targetSdk = 36
        versionCode = semverCode(cloudSyncVersionName)
        versionName = cloudSyncVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    androidResources {
        localeFilters += listOf("en")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        create("release") {
            val store = System.getenv("CLOUDSYNC_KEYSTORE_PATH")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("CLOUDSYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLOUDSYNC_KEY_ALIAS")
                keyPassword = System.getenv("CLOUDSYNC_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE",
                    "META-INF/LICENSE.txt",
                    "META-INF/LICENSE.md",
                    "META-INF/NOTICE",
                    "META-INF/NOTICE.txt",
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/INDEX.LIST",
                )
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":merge"))
    implementation(project(":versioning"))
    implementation(project(":network"))
    implementation(project(":nextcloud"))
    implementation(project(":synchronization"))
    implementation(project(":database"))
    implementation(project(":security"))
    implementation(project(":storage"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.viewmodel)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

tasks.register("requireReleaseSigning") {
    doLast {
        val required =
            listOf(
                "CLOUDSYNC_KEYSTORE_PATH",
                "CLOUDSYNC_KEYSTORE_PASSWORD",
                "CLOUDSYNC_KEY_ALIAS",
                "CLOUDSYNC_KEY_PASSWORD",
            )
        val missing = required.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing ${missing.joinToString()}. " +
                    "Debug builds do not need these secrets. See docs/github-actions.md.",
            )
        }
    }
}

tasks
    .matching { task ->
        val name = task.name
        name.contains("Release", ignoreCase = true) &&
            (
                name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package") || name.startsWith("sign") ||
                    name.startsWith("minify")
            )
    }.configureEach {
        dependsOn("requireReleaseSigning")
    }
