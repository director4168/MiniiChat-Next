// MiniiChat Next (com.miniichatNext.carter)
//
// Licensed under the GNU Affero General Public License v3.0.
// See ../LICENSE and ../AGPL_NOTICE.md for the full license text and
// third-party attributions.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.miniichatNext.carter"
    compileSdk = 36

    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.miniichatNext.carter"
        minSdk = 26
        targetSdk = 35
        versionCode = 26091218
        versionName = "1.2.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // 自动签名部分，用的是我（director_Carter）自己创建的签名，V2签
    signingConfigs {
        create("release") {
            val ksFile = file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: "miniichat_next_newauthor-director_carter"
                keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: "miniichat_next_newauthor-director_carter"
                keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: "miniichat_next_newauthor-director_carter"
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (file("release.keystore").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 关于页面要显示构建方式（debug/release）和构建日期
    buildTypes.all {
        buildConfigField("String", "BUILD_TYPE", "\"${this.name}\"")
        // buildConfigField 的 value 参数永远是 String（要嵌进 BuildConfig.java 字面量），
        // System.currentTimeMillis() 是 Long，必须 .toString()
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.ucrop)
    implementation(libs.androidx.transition)
    implementation(libs.androidx.appcompat)
}

val copyAndRenameReleaseApks = tasks.register("copyAndRenameReleaseApks") {
    group = "distribution"
    description = "Rename release APKs to MiniiChat-Next-<ver>-<abi>-release.apk and copy to build-outputs/release/"
    doLast {
        val srcDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        if (!srcDir.exists()) {
            logger.warn("No release APK output at $srcDir; skipping copyAndRenameReleaseApks")
            return@doLast
        }
        val versionName = android.defaultConfig.versionName
        val dstDir = file("${rootProject.projectDir}/build-outputs/release")
        dstDir.deleteRecursively()
        dstDir.mkdirs()
        srcDir.listFiles { _, name -> name.endsWith(".apk") }?.forEach { apk ->
            val abi = apk.nameWithoutExtension
                .removePrefix("app-")
                .substringBefore("-release")
            val finalName = "MiniiChat-Next-${versionName}-${abi}-release.apk"
            apk.copyTo(File(dstDir, finalName), overwrite = true)
            println("MiniiChat-Next: ${apk.name} -> build-outputs/release/$finalName")
        }
    }
}

val copyAndRenameDebugApks = tasks.register("copyAndRenameDebugApks") {
    group = "distribution"
    description = "Rename debug APKs to MiniiChat-Next-<ver>-<abi>-debug.apk and copy to build-outputs/debug/"
    doLast {
        val srcDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        if (!srcDir.exists()) {
            logger.warn("No debug APK output at $srcDir; skipping copyAndRenameDebugApks")
            return@doLast
        }
        val versionName = android.defaultConfig.versionName
        val dstDir = file("${rootProject.projectDir}/build-outputs/debug")
        dstDir.deleteRecursively()
        dstDir.mkdirs()
        srcDir.listFiles { _, name -> name.endsWith(".apk") }?.forEach { apk ->
            val abi = apk.nameWithoutExtension
                .removePrefix("app-")
                .substringBefore("-debug")
            val finalName = "MiniiChat-Next-${versionName}-${abi}-debug.apk"
            apk.copyTo(File(dstDir, finalName), overwrite = true)
            println("MiniiChat-Next: ${apk.name} -> build-outputs/debug/$finalName")
        }
    }
}

afterEvaluate {
    tasks.findByName("assembleRelease")?.finalizedBy(copyAndRenameReleaseApks)
    tasks.findByName("assembleDebug")?.finalizedBy(copyAndRenameDebugApks)
}
