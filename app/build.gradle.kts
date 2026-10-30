plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

import java.util.Properties
import java.io.FileInputStream
import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

data class RepoVersionInfo(
    val tag: String?,
    val commit: String?,
    val repoUrl: String
) {
    val displayValue: String
        get() = when {
            !tag.isNullOrBlank() && !commit.isNullOrBlank() -> "$tag - $commit"
            !commit.isNullOrBlank() -> commit
            !tag.isNullOrBlank() -> tag
            else -> "unknown"
        }

    val versionNameValue: String
        get() = when {
            !tag.isNullOrBlank() && !commit.isNullOrBlank() -> "$tag-$commit"
            !commit.isNullOrBlank() -> commit
            !tag.isNullOrBlank() -> tag
            else -> "0.0.1"
        }

    val commitUrl: String
        get() = if (!commit.isNullOrBlank()) "$repoUrl/commit/$commit" else repoUrl
}

fun runCommand(workingDir: File, vararg command: String): String? {
    return try {
        val output = providers.exec {
            workingDir(workingDir)
            commandLine(*command)
        }.standardOutput.asText.get().trim()
        if (output.isNotEmpty()) output else null
    } catch (e: Exception) {
        null
    }
}

fun normalizeGitHubUrl(remoteUrl: String?): String? {
    if (remoteUrl.isNullOrBlank()) return null
    return when {
        remoteUrl.startsWith("git@github.com:") -> {
            "https://github.com/" + remoteUrl.removePrefix("git@github.com:").removeSuffix(".git")
        }
        remoteUrl.startsWith("https://github.com/") -> remoteUrl.removeSuffix(".git")
        else -> null
    }
}

fun resolveGitRepoVersion(repoDir: File, fallbackRepoUrl: String, preferredTag: String? = null): RepoVersionInfo {
    val commit = runCommand(repoDir, "git", "rev-parse", "--short", "HEAD")
    val tag = preferredTag
        ?: runCommand(repoDir, "git", "describe", "--tags", "--abbrev=0")?.removePrefix("v")
    val repoUrl = normalizeGitHubUrl(runCommand(repoDir, "git", "remote", "get-url", "origin"))
        ?: fallbackRepoUrl

    return RepoVersionInfo(tag = tag, commit = commit, repoUrl = repoUrl)
}

fun resolveCargoGitDependencyVersion(lockFile: File, packageName: String, fallbackRepoUrl: String): RepoVersionInfo {
    if (!lockFile.exists()) return RepoVersionInfo(tag = null, commit = null, repoUrl = fallbackRepoUrl)
    val block = lockFile.readText()
        .split("[[package]]")
        .firstOrNull { it.lineSequence().any { l -> l.trim() == "name = \"$packageName\"" } }
        ?: return RepoVersionInfo(tag = null, commit = null, repoUrl = fallbackRepoUrl)
    val version = Regex("version = \"([^\"]+)\"").find(block)?.groupValues?.get(1)
    val source = Regex("source = \"([^\"]+)\"").find(block)?.groupValues?.get(1)
    val commit = source
        ?.let { Regex("#([0-9a-f]{7,40})").find(it)?.groupValues?.get(1) }
        ?.take(7)
    val repoUrl = source
        ?.removePrefix("git+")
        ?.substringBefore("#")
        ?.removeSuffix(".git")
        ?.takeIf { it.isNotBlank() }
        ?: fallbackRepoUrl
    return RepoVersionInfo(tag = version, commit = commit, repoUrl = repoUrl)
}

val appVersionInfo by lazy {
    resolveGitRepoVersion(
        repoDir = rootProject.projectDir,
        fallbackRepoUrl = "https://github.com/lespromtehkms/yggstack-android"
    )
}

val goYggstackVersionInfo by lazy {
    resolveGitRepoVersion(
        repoDir = rootProject.file("lib/yggstack"),
        fallbackRepoUrl = "https://github.com/DrewCyber/yggstack"
    )
}

val goYggdrasilVersionInfo by lazy {
    RepoVersionInfo(
        tag = "0.5.15.1-alpha2",
        commit = "develop",
        repoUrl = "https://github.com/lespromtehkms/yggdrasil-go"
    )
}

val ngYggstackVersionInfo by lazy {
    resolveGitRepoVersion(
        repoDir = rootProject.file("lib/yggstack-ng"),
        fallbackRepoUrl = "https://github.com/DrewCyber/yggstack-ng"
    )
}

val ngYggdrasilVersionInfo by lazy {
    resolveCargoGitDependencyVersion(
        lockFile = rootProject.file("lib/yggstack-ng/Cargo.lock"),
        packageName = "yggdrasil",
        fallbackRepoUrl = "https://github.com/Revertron/Yggdrasil-ng"
    )
}

fun getVersionName(): String {
    val envVersion = System.getenv("APP_VERSION")
    if (!envVersion.isNullOrEmpty()) {
        return envVersion
    }

    return appVersionInfo.versionNameValue
}

fun getVersionCode(): Int {
    return try {
        val versionName = getVersionName()
        val version = versionName.split("-")[0]
        val parts = version.split(".")
        
        if (parts.size >= 3) {
            val major = parts[0].toIntOrNull() ?: 0
            val minor = parts[1].toIntOrNull() ?: 0
            val patch = parts[2].toIntOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } else {
            1
        }
    } catch (e: Exception) {
        1
    }
}

fun getCommitHash(): String {
    return appVersionInfo.commit ?: "unknown"
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "link.yggdrasil.yggstack.android"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "link.yggdrasil.yggstack.android.alpha2"
        minSdk = 23
        targetSdk = 34
        versionCode = getVersionCode()
        versionName = getVersionName()

        buildConfigField("String", "VERSION_NAME", "\"${getVersionName()}\"")
        buildConfigField("String", "COMMIT_HASH", "\"${getCommitHash()}\"")
        buildConfigField("String", "APP_VERSION_DISPLAY", "\"${appVersionInfo.displayValue}\"")
        buildConfigField("String", "APP_VERSION_URL", "\"${appVersionInfo.commitUrl}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += listOf("engine")
    productFlavors {
        create("go") {
            dimension = "engine"
            isDefault = true
            buildConfigField("String", "ENGINE_ID", "\"go\"")
            buildConfigField("boolean", "ENGINE_SUPPORTS_CKR", "false")
            buildConfigField("String", "YGGSTACK_VERSION_DISPLAY", "\"${goYggstackVersionInfo.displayValue}\"")
            buildConfigField("String", "YGGSTACK_VERSION_URL", "\"${goYggstackVersionInfo.commitUrl}\"")
            buildConfigField("String", "YGGDRASIL_VERSION_DISPLAY", "\"${goYggdrasilVersionInfo.displayValue}\"")
            buildConfigField("String", "YGGDRASIL_VERSION_URL", "\"${goYggdrasilVersionInfo.repoUrl}\"")
        }
        create("ng") {
            dimension = "engine"
            applicationIdSuffix = ".ng"
            buildConfigField("String", "ENGINE_ID", "\"ng\"")
            buildConfigField("boolean", "ENGINE_SUPPORTS_CKR", "true")
            buildConfigField("String", "YGGSTACK_VERSION_DISPLAY", "\"${ngYggstackVersionInfo.displayValue}\"")
            buildConfigField("String", "YGGSTACK_VERSION_URL", "\"${ngYggstackVersionInfo.commitUrl}\"")
            buildConfigField("String", "YGGDRASIL_VERSION_DISPLAY", "\"${ngYggdrasilVersionInfo.displayValue}\"")
            buildConfigField("String", "YGGDRASIL_VERSION_URL", "\"${ngYggdrasilVersionInfo.commitUrl}\"")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE") ?: "../release.keystore"
            val keystoreFile = if (System.getenv("KEYSTORE_FILE") != null) {
                file(keystorePath)
            } else {
                val localKeystore = file("../release.keystore")
                if (localKeystore.exists()) localKeystore else null
            }
            
            if (keystoreFile != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") 
                    ?: keystoreProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") 
                    ?: keystoreProperties.getProperty("KEY_ALIAS") 
                    ?: "release"
                keyPassword = System.getenv("KEY_PASSWORD") 
                    ?: keystoreProperties.getProperty("KEY_PASSWORD") 
                    ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            val releaseSigningConfig = signingConfigs.getByName("release")
            if (releaseSigningConfig.storeFile?.exists() == true) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    add("goImplementation", files("libs/yggstack.aar"))
    add("ngImplementation", "net.java.dev.jna:jna:5.14.0@aar")

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.navigation.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.dnsjava)
    implementation(libs.miui.autostart)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
