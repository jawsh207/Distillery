// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import app.distillery.stt.service.DistilleryRecognitionService

/** Helpers for making Distillery the phone's default voice input. */
object VoiceInputSetup {

    /** Settings.Secure key holding the default recognition service (readable by apps). */
    private const val VOICE_RECOGNITION_SERVICE = "voice_recognition_service"

    fun component(context: Context) = ComponentName(context, DistilleryRecognitionService::class.java)

    /** The current default recognition service, or null if none or unreadable. */
    fun currentDefault(context: Context): ComponentName? = runCatching {
        Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE)
            ?.takeIf { it.isNotBlank() }
            ?.let(ComponentName::unflattenFromString)
    }.getOrNull()

    fun isDefault(context: Context): Boolean = currentDefault(context) == component(context)

    fun adbCommand(context: Context): String =
        "adb shell settings put secure $VOICE_RECOGNITION_SERVICE ${component(context).flattenToShortString()}"

    /** Code another app (like a keyboard) can use to call Distillery directly, whatever the default. */
    fun directUseSnippet(context: Context): String {
        val c = component(context)
        return "SpeechRecognizer.createSpeechRecognizer(\n" +
            "    context,\n" +
            "    ComponentName(\"${c.packageName}\", \"${c.className}\"),\n" +
            ")"
    }

    /**
     * Opens the system Languages page (Language & region on newer releases),
     * whose Speech section holds the Voice input picker Distillery appears in.
     *
     * Android has no public shortcut to that picker. ACTION_VOICE_INPUT_SETTINGS
     * is not it: on AOSP, GrapheneOS and Pixels it opens the default digital
     * assistant picker, which lists only full assistant apps. The Languages page
     * is opened through Settings' exported "android.settings.LANGUAGE_SETTINGS"
     * action, with the Voice input row highlighted where Settings supports that.
     */
    fun openVoiceInputSettings(context: Context) {
        val highlight = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, VOICE_INPUT_PREFERENCE) }
        val candidates = listOf(
            Intent(ACTION_LANGUAGE_SETTINGS)
                .putExtra(EXTRA_FRAGMENT_ARG_KEY, VOICE_INPUT_PREFERENCE)
                .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, highlight),
            Intent(Settings.ACTION_LOCALE_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /** Exported by AOSP Settings for its Languages page; not in the public SDK. */
    private const val ACTION_LANGUAGE_SETTINGS = "android.settings.LANGUAGE_SETTINGS"

    /** Settings' extras for scrolling to and highlighting a row. */
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    private const val VOICE_INPUT_PREFERENCE = "voice_input_settings"

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
