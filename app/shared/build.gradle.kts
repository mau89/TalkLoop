import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Ключ читаем из secrets.properties в корне (файл в .gitignore) — тем же способом,
// что и androidApp. В собранный фреймворк он вкомпилируется, поэтому сборку никому
// не раздавать; чтобы убрать ключ с клиента, понадобится прокси через :server.
val anthropicApiKey: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("secrets.properties"))
    .asText
    .map { text ->
        text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("anthropic.api.key=") }
            ?.substringAfter("=")
            ?.trim()
            .orEmpty()
    }
    .getOrElse("")

// У iOS нет BuildConfig, поэтому генерируем эквивалент сами.
val iosSecretsDir = layout.buildDirectory.dir("generated/iosSecrets/kotlin")

val generateIosSecrets = tasks.register("generateIosSecrets") {
    // Локальные копии, а не ссылки на скрипт: иначе configuration cache не сериализуется.
    val key = anthropicApiKey
    val dir = iosSecretsDir
    inputs.property("apiKey", key)
    outputs.dir(dir)
    doLast {
        val file = dir.get().asFile.resolve("com/mau89/talkloop/Secrets.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.mau89.talkloop

            // Генерируется Gradle из secrets.properties. Руками не править.
            internal const val IOS_ANTHROPIC_API_KEY: String = "$key"
            """.trimIndent() + "\n"
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.mau89.talkloop.app.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        iosMain {
            kotlin.srcDir(generateIosSecrets)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}