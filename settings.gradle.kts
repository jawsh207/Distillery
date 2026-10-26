pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Distillery"
include(":app")

// Build Moonshine (and its ONNX Runtime) from source instead of using the
// prebuilt AAR from Maven Central. This is the path for F-Droid and for
// reproducible builds; see docs/BUILDING.md.
//
//   ./gradlew assembleRelease -Pdistillery.moonshineSource=third_party/moonshine
//
// The directory must be a checkout of github.com/moonshine-ai/moonshine at the
// tag matching `moonshineVoice` in gradle/libs.versions.toml, with
// scripts/build-ort-android.sh already run so the vendored ONNX Runtime
// libraries are rebuilt from source.
val moonshineSource = providers.gradleProperty("distillery.moonshineSource").orNull
if (!moonshineSource.isNullOrBlank()) {
    includeBuild("$moonshineSource/language-bindings/android") {
        dependencySubstitution {
            substitute(module("ai.moonshine:moonshine-voice")).using(project(":"))
        }
    }
}
