plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

val aiTaskParserUrl = providers.gradleProperty("AI_TASK_PARSER_URL")
    .orElse("")
    .get()
val aiImageTaskParserUrl = providers.gradleProperty("AI_IMAGE_TASK_PARSER_URL")
    .orElse("https://api.primarytask.top/parse-image-task")
    .get()

android {
    namespace = "com.ouyue.ji"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ouyue.ji"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "2.0"
        manifestPlaceholders["allowCleartextTraffic"] = "false"
        buildConfigField(
            "String",
            "AI_TASK_PARSER_URL",
            "\"${aiTaskParserUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
        buildConfigField(
            "String",
            "AI_IMAGE_TASK_PARSER_URL",
            "\"${aiImageTaskParserUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            manifestPlaceholders["allowCleartextTraffic"] = "true"
        }
        release {
            isDebuggable = false
            manifestPlaceholders["allowCleartextTraffic"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        doFirst {
            listOf(aiTaskParserUrl, aiImageTaskParserUrl)
                .filter { it.isNotBlank() }
                .forEach { endpoint ->
                    if (!endpoint.startsWith("https://", ignoreCase = true)) {
                        throw GradleException("Release parser endpoints must use HTTPS")
                    }
                }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.google.mlkit.text.recognition.chinese)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    kapt(libs.androidx.room.compiler)
}
