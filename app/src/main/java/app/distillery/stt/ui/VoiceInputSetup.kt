// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /** Opens the system voice input picker, falling back to language settings. */
    fun openVoiceInputSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_LOCALE_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
