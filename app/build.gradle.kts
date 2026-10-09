import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        FileInputStream(versionPropsFile).use { load(it) }
    }
}
val vMajor = versionProps.getProperty("versionMajor", "1").toInt()
val vMinor = versionProps.getProperty("versionMinor", "0").toInt()
val vPatch = versionProps.getProperty("versionPatch", "1").toInt()
val vBuild = versionProps.getProperty("versionBuild", "2").toInt()

val currentVersionCode = vBuild
val currentVersionName = "$vMajor.$vMinor.$vPatch"

android {
    namespace = "com.alphanew.deploy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alphanew.deploy"
        minSdk = 26
        targetSdk = 28
        versionCode = currentVersionCode
        versionName = currentVersionName

        buildConfigField("String", "VERSION_NAME", "\"$currentVersionName\"")
        buildConfigField("int", "VERSION_CODE", "$currentVersionCode")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Gradle task to bump version patch and build code automatically
tasks.register("bumpVersion") {
    doLast {
        val nextPatch = vPatch + 1
        val nextBuild = vBuild + 1
        versionProps.setProperty("versionPatch", nextPatch.toString())
        versionProps.setProperty("versionBuild", nextBuild.toString())
        FileOutputStream(versionPropsFile).use { versionProps.store(it, "Updated by bumpVersion task") }
        println("BUMPED VERSION to $vMajor.$vMinor.$nextPatch (Build $nextBuild)")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core-model"))
    implementation(project(":core-database"))
    implementation(project(":core-supervisor"))
    implementation(project(":core-projects"))
    implementation(project(":core-proxy"))
    implementation(project(":core-packages"))
    implementation(project(":core-backup"))
    implementation(project(":core-antikill"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
}
