plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val mlc4jPresent: Boolean =
    rootProject.layout.projectDirectory.file("mlc4j/build.gradle").asFile.isFile

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "me.lekseg.aiapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "me.lekseg.aiapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "boolean",
            "MLC_AVAILABLE",
            if (mlc4jPresent) "true" else "false",
        )
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.named("main").configure {
        assets.srcDir(
            rootProject.layout.projectDirectory
                .dir("composeApp/build/intermediates/assets/debug/mergeDebugAssets")
                .asFile,
        )
    }
}

afterEvaluate {
    listOf("mergeDebugAssets", "mergeReleaseAssets").forEach { taskName ->
        tasks.named(taskName).configure {
            dependsOn(":composeApp:bundleLibRuntimeToDirAndroidMain")
        }
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.mlcApi)
    implementation(libs.androidx.activity.compose)
    implementation(libs.composeMaterial3)
    implementation(libs.composeUiToolingPreview)
    debugImplementation(libs.composeUiTooling)
    if (mlc4jPresent) {
        implementation(project(":androidMlcExt"))
    }
}
