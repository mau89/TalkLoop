import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Ключ читаем из secrets.properties в корне (файл в .gitignore, в репозиторий не попадает).
// В собранный APK он всё же вкомпилируется — раздавать сборку нельзя, см. README.
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

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.mau89.talkloop"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mau89.talkloop"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}