// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui.screens

/** One component shown on the licenses screen; [asset] is under assets/licenses/. */
data class OssComponent(
    val id: String,
    val name: String,
    val detail: String,
    val license: String,
    val asset: String,
)

data class OssGroup(val title: String, val components: List<OssComponent>)

/**
 * Everything Distillery ships or downloads, with its license. Keep in step with
 * gradle/libs.versions.toml and Moonshine's core/third-party folder.
 */
object OssLicenses {
    val groups = listOf(
        OssGroup(
            "This app",
            listOf(
                OssComponent(
                    "distillery", "Distillery", "Copyright © 2026 the Distillery contributors",
                    "GPL-3.0-or-later", "GPL-3.0.txt",
                ),
            ),
        ),
        OssGroup(
            "Speech models (downloaded on request)",
            listOf(
                OssComponent(
                    "model-tiny", "Moonshine Tiny Streaming (English)", "Moonshine AI · 34M parameters",
                    "MIT", "MIT-moonshine.txt",
                ),
                OssComponent(
                    "model-small", "Moonshine Small Streaming (English)", "Moonshine AI · 123M parameters",
                    "MIT", "MIT-moonshine.txt",
                ),
                OssComponent(
                    "model-medium", "Moonshine Medium Streaming (English)", "Moonshine AI · 245M parameters",
                    "MIT", "MIT-moonshine.txt",
                ),
            ),
        ),
        OssGroup(
            "Speech engine (native code)",
            listOf(
                OssComponent(
                    "moonshine", "Moonshine Voice 0.1.6", "Useful Sensors, Inc. (dba Moonshine AI)",
                    "MIT", "MIT-moonshine.txt",
                ),
                OssComponent(
                    "onnxruntime", "ONNX Runtime 1.23.2 (minimal build)", "Microsoft Corporation",
                    "MIT", "MIT-onnxruntime.txt",
                ),
                OssComponent(
                    "onnxruntime-notices", "ONNX Runtime third-party notices", "Components inside ONNX Runtime",
                    "Various (see notices)", "onnxruntime-ThirdPartyNotices.txt",
                ),
                OssComponent(
                    "eigen", "Eigen", "MPL-2.0 subset only",
                    "MPL-2.0", "MPL-2.0.txt",
                ),
                OssComponent(
                    "kaldi-native-fbank", "kaldi-native-fbank", "Audio feature extraction",
                    "Apache-2.0", "Apache-2.0-kaldi-native-fbank.txt",
                ),
                OssComponent(
                    "kissfft", "KISS FFT", "Mark Borgerding",
                    "BSD-3-Clause", "BSD-3-Clause-kissfft.txt",
                ),
                OssComponent(
                    "nlohmann-json", "JSON for Modern C++", "Niels Lohmann",
                    "MIT", "MIT-nlohmann-json.txt",
                ),
                OssComponent(
                    "utf8proc", "utf8proc", "Steven G. Johnson, Jiahao Chen and contributors",
                    "MIT", "MIT-utf8proc.txt",
                ),
                OssComponent(
                    "utfcpp", "UTF8-CPP", "Nemanja Trifunovic",
                    "BSL-1.0", "BSL-1.0-utfcpp.txt",
                ),
            ),
        ),
        OssGroup(
            "Android libraries",
            listOf(
                OssComponent(
                    "androidx", "AndroidX and Jetpack Compose",
                    "Core, Activity, Lifecycle, Navigation, WorkManager, AppCompat, Compose UI, Material 3",
                    "Apache-2.0", "Apache-2.0.txt",
                ),
                OssComponent(
                    "mdc", "Material Components for Android", "Used by Moonshine Voice",
                    "Apache-2.0", "Apache-2.0.txt",
                ),
                OssComponent(
                    "material-icons", "Material Icons", "Google",
                    "Apache-2.0", "Apache-2.0.txt",
                ),
                OssComponent(
                    "kotlin", "Kotlin standard library and kotlinx.coroutines", "JetBrains",
                    "Apache-2.0", "Apache-2.0.txt",
                ),
                OssComponent(
                    "okhttp", "OkHttp and Okio", "Square, Inc. (model downloads)",
                    "Apache-2.0", "Apache-2.0.txt",
                ),
                OssComponent(
                    "guava-listenablefuture", "Guava ListenableFuture", "Google (used by WorkManager)",
                    "Apache-2.0", "Apache-2.0.txt",
                ),
            ),
        ),
    )

    fun find(id: String): OssComponent? = groups.flatMap { it.components }.firstOrNull { it.id == id }
}
