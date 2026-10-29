import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.distillery.stt"
    // Current Compose and Material 3 releases require compiling against API 37.
    // The app still runs on Android 16 (minSdk 36).
    compileSdk = 37

    defaultConfig {
        applicationId = "app.distillery.stt"
        minSdk = 36
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = false
    }

    // Release signing. Values come from environment variables (CI secrets) or,
    // for local builds, from keystore.properties at the project root (never
    // committed). Without them the release build is left unsigned.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    fun signingValue(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

    val releaseStoreFile = signingValue("RELEASE_KEYSTORE_FILE", "storeFile")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("RELEASE_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    // Native code (Moonshine, ONNX Runtime) per ABI. arm64-v8a covers every
    // Android 16 phone, so releases carry only that; dev builds also carry
    // x86_64 for the emulator. (Set per build type: filters set on
    // defaultConfig would add to these rather than be replaced.)
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    // F-Droid and reproducible builds: don't embed the Google-signed
    // dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The full ONNX Runtime.
//
// Moonshine's AAR ships an ONNX Runtime cut down to the operators of its own
// models, and the 0.1.5 build lacks kernels the Kokoro text-to-speech models
// need (QLinearMatMul). Every build replaces it with the stock ONNX Runtime
// Android library of the same version, which has every operator; speech
// recognition runs on it unchanged.
val stockOrt: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

abstract class ExtractStockOrt : DefaultTask() {
    @get:InputFiles abstract val aar: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun extract() {
        val aarFile = aar.singleFile
        fs.sync {
            from(archives.zipTree(aarFile)) {
                include("jni/arm64-v8a/libonnxruntime.so", "jni/x86_64/libonnxruntime.so")
                eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
    }
}

val extractStockOrt = tasks.register<ExtractStockOrt>("extractStockOrt") {
    aar.from(stockOrt)
    outputDir.set(layout.buildDirectory.dir("generated/stockOrtJni"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(extractStockOrt, ExtractStockOrt::outputDir)
        // The app's own copy is picked over the one inside Moonshine's AAR;
        // CI checks the APKs really carry the stock library.
        variant.packaging.jniLibs.pickFirsts.add("**/libonnxruntime.so")
    }
}

dependencies {
    stockOrt(libs.onnxruntime.android)
    implementation(libs.moonshine.voice)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation(libs.org.json)
}
