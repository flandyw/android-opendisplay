import java.io.Serializable

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version = git commit count (the same scheme as Folio's release server):
//   stable code  = count * 10000          name = X.Y.Z   (count/100 . count/10%10 . count%10)
//   experimental = count * 10000 + N      name = X.Y.Z-exp.N (N reserved by build.sh, 1..9999)
// so every experimental build of a commit sorts below the next commit's build.
data class AppVersion(val code: Int, val name: String) : Serializable

val commitCount = providers.exec {
    commandLine("git", "-C", rootProject.projectDir.absolutePath, "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toInt() }
val checkedCommitCount = commitCount.zip(
    providers.gradleProperty("opendisplayExperimentalCommitCount").orElse("")
) { count, expected ->
    require(expected.isEmpty() || expected.toIntOrNull() == count) {
        "HEAD changed after reserving the experimental build; rerun build.sh"
    }
    count
}
val experimentalBuild = providers.gradleProperty("opendisplayExperimentalBuild").map {
    it.toIntOrNull() ?: error("opendisplayExperimentalBuild must be an integer")
}.orElse(0)
val automaticVersion = checkedCommitCount.zip(experimentalBuild) { count, experimental ->
    require(count in 1..210_000 && experimental in 0..9999) {
        "Commit count or experimental build number is out of range"
    }
    val code = count.toLong() * 10_000 + experimental
    require(code in 1..2_100_000_000L) { "Generated Android versionCode is out of range" }
    val stableName = "${count / 100}.${(count / 10) % 10}.${count % 10}"
    AppVersion(code.toInt(), if (experimental == 0) stableName else "$stableName-exp.$experimental")
}

android {
    namespace = "app.opendisplay.receiver"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.opendisplay.receiver"
        minSdk = 26
        // targetSdk stays at 35: no new API 36 runtime behavior is opted into yet.
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // build.sh supplies ANDROID_KEYSTORE_PATH/PASSWORD; assembleRelease fails without them.
        create("release") {
            storeFile = file(providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                .orElse("missing-release-keystore.p12").get())
            storeType = "PKCS12"
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
            keyAlias = "opendisplay"
            keyPassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 is slow, so it is opt-in locally (-PopendisplayMinify=true, build.sh -r).
            val minify = providers.gradleProperty("opendisplayMinify").orNull == "true"
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Built-in Kotlin (AGP 9) defaults jvmTarget to compileOptions.targetCompatibility
    // (Java 17), so no kotlin.compilerOptions.jvmTarget is needed here.

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Versioned APK names, e.g. OpenDisplay-2.5.5-exp.1.apk / OpenDisplay-2.5.5-debug.apk
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "debug") "-debug" else ""
        variant.outputs.forEach { output ->
            output.versionCode.set(automaticVersion.map { it.code })
            output.versionName.set(automaticVersion.map { it.name + suffix })
            output.outputFileName.set(automaticVersion.map { "OpenDisplay-${it.name}$suffix.apk" })
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Expressive APIs are published in the 1.5 alpha line, not stable Material 3 1.4.
    implementation("androidx.compose.material3:material3:1.5.0-alpha01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // android.jar stubs org.json; the update parser is unit-tested on the JVM.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Copy assembled APKs into $HOME for easy adb install / sharing.
// Plain file copy (not Copy task) — Gradle must not snapshot all of $HOME.
fun registerCopyApkToHome(variantName: String) {
    val copyTaskName = "copy${variantName.replaceFirstChar { it.uppercase() }}ApkToHome"
    val assembleTaskName = "assemble${variantName.replaceFirstChar { it.uppercase() }}"
    val suffix = if (variantName == "debug") "-debug" else ""
    val apkFileName = automaticVersion.map { "OpenDisplay-${it.name}$suffix.apk" }
    val home = System.getProperty("user.home")
    val apk = layout.buildDirectory.file(apkFileName.map { "outputs/apk/$variantName/$it" })

    tasks.register(copyTaskName) {
        description = "Copy $variantName APK to $home"
        group = "build"
        inputs.file(apk)
        doLast {
            val src = apk.get().asFile
            require(src.isFile) { "Missing APK: $src" }
            val dest = file("$home/${src.name}")
            src.copyTo(dest, overwrite = true)
            println("APK → $dest")
        }
    }

    tasks.matching { it.name == assembleTaskName }.configureEach {
        finalizedBy(copyTaskName)
    }
}

registerCopyApkToHome("debug")
registerCopyApkToHome("release")
