import com.mikepenz.aboutlibraries.plugin.BaseAboutLibrariesTask
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val packageName = "vip.mystery0.pixel.text"
val gitVersionCode: Int = providers.exec {
    commandLine(
        "git",
        "rev-list",
        "HEAD",
        "--count"
    )
}.standardOutput.asText.get().trim().toInt()
val gitVersionName: String =
    providers.exec {
        commandLine(
            "git",
            "rev-parse",
            "--short=8",
            "HEAD"
        )
    }.standardOutput.asText.get().trim()
val appVersionName: String = libs.versions.app.version.get()

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

android {
    namespace = packageName
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt())
    }

    defaultConfig {
        applicationId = packageName
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = gitVersionCode
        versionName = appVersionName
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("sign")
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = ".d$gitVersionCode.$gitVersionName"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            versionNameSuffix = ".r$gitVersionCode.$gitVersionName"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("sign")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
    }
    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters.add("en")
        localeFilters.add("zh-rCN")
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.re2j)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.components)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material.icons.core)
    implementation(libs.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.jsoup)
    implementation(libs.ezvcard)
    implementation(libs.androidx.webkit)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.zhanghai.preference)

    implementation(libs.tensorflow.lite)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.zip4j)
    implementation(libs.smartspacer.sdk.plugin)
    ksp(libs.androidx.room.compiler)
    ksp(libs.moshi.kotlin.codegen)
}

// 将仓库中已有原文转换成插件配置，避免再维护一份转义后的许可证正文。
val noticeRoot = rootProject.layout.projectDirectory.asFile
val noticeMetadata = rootProject.file("config/open-source/notices.json")
val licenseOverrides = rootProject.file("config/open-source/license-overrides.json")
val generatedNotices = layout.buildDirectory.dir("generated/openSourceNotices")
val generateOpenSourceNotices = tasks.register("generateOpenSourceNotices") {
    inputs.file(noticeMetadata)
    inputs.file(licenseOverrides)
    inputs.files(rootProject.fileTree("docs/licenses") { exclude("*.md") })
    inputs.file(rootProject.file("app/src/main/assets/licenses/re2j-LICENSE.txt"))
    outputs.dir(generatedNotices)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val entries = JsonSlurper().parse(noticeMetadata, "UTF-8") as List<Map<String, Any>>
        val output = generatedNotices.get().asFile
        output.deleteRecursively()
        val libraries = output.resolve("libraries").apply { mkdirs() }
        val licenses = output.resolve("licenses").apply { mkdirs() }
        // 个别 POM 使用非 SPDX 名称；按插件生成的 ID 补上已有的上游原文。
        @Suppress("UNCHECKED_CAST")
        val overrides = JsonSlurper().parse(licenseOverrides, "UTF-8") as List<Map<String, String>>
        overrides.forEach { entry ->
            val license = entry.filterKeys { it != "source" }.toMutableMap()
            license["content"] = noticeRoot.resolve(entry.getValue("source")).readText(Charsets.UTF_8)
            licenses.resolve("${entry.getValue("hash")}.json").writeText(JsonOutput.toJson(license), Charsets.UTF_8)
        }
        entries.forEach { entry ->
            val id = entry.getValue("uniqueId") as String
            val key = "pixeltext-" + id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            @Suppress("UNCHECKED_CAST")
            val files = entry.getValue("noticeFiles") as List<String>
            val content = buildString {
                (entry["copyright"] as? String)?.let { append(it).append("\n\n") }
                files.forEach { path ->
                    append("===== ").append(path.substringAfterLast('/')).append(" =====\n\n")
                    append(noticeRoot.resolve(path).readText(Charsets.UTF_8)).append("\n\n")
                }
            }
            val library = entry.filterKeys { it != "noticeFiles" && it != "copyright" }.toMutableMap()
            library["licenses"] = listOf(key)
            libraries.resolve("$key.json").writeText(JsonOutput.toJson(library), Charsets.UTF_8)
            licenses.resolve("$key.json").writeText(JsonOutput.toJson(mapOf(
                "hash" to key,
                "name" to "原始许可与版权声明",
                "content" to content,
            )), Charsets.UTF_8)
        }
    }
}

aboutLibraries {
    collect {
        configPath.set(generatedNotices)
        // 不向 GitHub 查询库信息；SPDX 正文由插件在构建期获取并随 APK 打包。
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
}
tasks.withType<BaseAboutLibrariesTask>().configureEach {
    dependsOn(generateOpenSourceNotices)
}

apply(from = rootProject.file("signing.gradle"))
