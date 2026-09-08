import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    
    jvm()
    
    android {
       namespace = "com.mau89.talkloop.core"
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
    }
    
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: схема формата ответа (JsonObject) торчит
            // в публичном API LlmClient, значит нужна и потребителям core.
            api(libs.kotlinx.serializationJson)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationJson)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.clientDarwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}