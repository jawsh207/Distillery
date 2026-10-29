// SPDX-License-Identifier: GPL-3.0-or-later
package app.distillery.stt.tts

import java.util.Locale

enum class Accent(val label: String, val locale: Locale, val iso3Country: String) {
    AMERICAN("American English", Locale.US, "USA"),
    BRITISH("British English", Locale.UK, "GBR");

    companion object {
        /** Matches "US"/"USA" and "GB"/"GBR" (TTS callers use either form). */
        fun fromCountry(country: String?): Accent? = when (country?.uppercase(Locale.ROOT)) {
            "US", "USA" -> AMERICAN
            "GB", "GBR", "UK" -> BRITISH
            else -> null
        }
    }
}

enum class Gender(val label: String) { FEMALE("Female"), MALE("Male") }

/**
 * The English Kokoro-82M voices Distillery offers (hexgrad/Kokoro-82M, Apache-2.0).
 *
 * This is an allowlist: only these ids are ever downloaded or loaded. Moonshine's
 * catalog also has other languages, Piper voices and ZipVoice voice cloning;
 * none of those are used. The [id] is Kokoro's own voice name; Moonshine
 * addresses it as `kokoro_<id>`.
 */
enum class KokoroVoice(val id: String, val displayName: String, val accent: Accent, val gender: Gender) {
    AF_HEART("af_heart", "Heart", Accent.AMERICAN, Gender.FEMALE),
    AF_BELLA("af_bella", "Bella", Accent.AMERICAN, Gender.FEMALE),
    AF_NICOLE("af_nicole", "Nicole", Accent.AMERICAN, Gender.FEMALE),
    AF_AOEDE("af_aoede", "Aoede", Accent.AMERICAN, Gender.FEMALE),
    AF_KORE("af_kore", "Kore", Accent.AMERICAN, Gender.FEMALE),
    AF_SARAH("af_sarah", "Sarah", Accent.AMERICAN, Gender.FEMALE),
    AF_NOVA("af_nova", "Nova", Accent.AMERICAN, Gender.FEMALE),
    AF_ALLOY("af_alloy", "Alloy", Accent.AMERICAN, Gender.FEMALE),
    AF_JESSICA("af_jessica", "Jessica", Accent.AMERICAN, Gender.FEMALE),
    AF_RIVER("af_river", "River", Accent.AMERICAN, Gender.FEMALE),
    AF_SKY("af_sky", "Sky", Accent.AMERICAN, Gender.FEMALE),
    AM_MICHAEL("am_michael", "Michael", Accent.AMERICAN, Gender.MALE),
    AM_FENRIR("am_fenrir", "Fenrir", Accent.AMERICAN, Gender.MALE),
    AM_PUCK("am_puck", "Puck", Accent.AMERICAN, Gender.MALE),
    AM_ECHO("am_echo", "Echo", Accent.AMERICAN, Gender.MALE),
    AM_ERIC("am_eric", "Eric", Accent.AMERICAN, Gender.MALE),
    AM_LIAM("am_liam", "Liam", Accent.AMERICAN, Gender.MALE),
    AM_ONYX("am_onyx", "Onyx", Accent.AMERICAN, Gender.MALE),
    AM_ADAM("am_adam", "Adam", Accent.AMERICAN, Gender.MALE),
    AM_SANTA("am_santa", "Santa", Accent.AMERICAN, Gender.MALE),
    BF_EMMA("bf_emma", "Emma", Accent.BRITISH, Gender.FEMALE),
    BF_ISABELLA("bf_isabella", "Isabella", Accent.BRITISH, Gender.FEMALE),
    BF_ALICE("bf_alice", "Alice", Accent.BRITISH, Gender.FEMALE),
    BF_LILY("bf_lily", "Lily", Accent.BRITISH, Gender.FEMALE),
    BM_GEORGE("bm_george", "George", Accent.BRITISH, Gender.MALE),
    BM_FABLE("bm_fable", "Fable", Accent.BRITISH, Gender.MALE),
    BM_LEWIS("bm_lewis", "Lewis", Accent.BRITISH, Gender.MALE),
    BM_DANIEL("bm_daniel", "Daniel", Accent.BRITISH, Gender.MALE);

    /** The voice id Moonshine's synthesizer and asset catalog use. */
    val moonshineId: String get() = "kokoro_$id"

    /** The name this voice has in Android's TTS voice list. */
    val voiceName: String get() = "en-${accent.locale.country.lowercase(Locale.ROOT)}-x-distillery-$id"

    companion object {
        val DEFAULT = AF_HEART

        fun fromId(id: String?): KokoroVoice? = entries.firstOrNull { it.id == id }

        fun fromVoiceName(name: String?): KokoroVoice? =
            entries.firstOrNull { it.voiceName == name || it.moonshineId == name || it.id == name }
    }
}
