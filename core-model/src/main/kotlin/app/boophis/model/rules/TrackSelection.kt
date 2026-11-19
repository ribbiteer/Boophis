// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.model.rules

import app.boophis.model.TrackInfo
import app.boophis.model.TrackType

/**
 * KICKOFF §6 auto track selection:
 * - Audio: prefer English (the default-flagged English track first), else the default track, else the first.
 * - Subtitles: off, unless (a) a forced track exists in the audio's language (or English / unknown),
 *   or (b) the chosen audio is known and not English and an English track exists.
 *
 * Indices refer to positions within the list passed in (so callers can map back to engine tracks).
 */
object TrackSelection {
    data class Choice(val audioIndex: Int?, val subtitleIndex: Int?)

    fun choose(audio: List<TrackInfo>, subtitles: List<TrackInfo>): Choice {
        val a = chooseAudio(audio)
        val audioLang = a?.let { normalize(audio[it].language) }
        return Choice(a, chooseSubtitle(subtitles, audioLang))
    }

    fun chooseAudio(audio: List<TrackInfo>): Int? {
        if (audio.isEmpty()) return null
        val english = audio.indices.filter { isEnglish(audio[it].language) }
        if (english.isNotEmpty()) {
            return english.firstOrNull { audio[it].isDefault } ?: english.first()
        }
        return audio.indices.firstOrNull { audio[it].isDefault } ?: 0
    }

    /** [audioLanguage] is normalized (ISO 639-1-ish, lowercase) or null if unknown. */
    fun chooseSubtitle(subtitles: List<TrackInfo>, audioLanguage: String?): Int? {
        if (subtitles.isEmpty()) return null
        val forced = subtitles.indices.filter { subtitles[it].isForced }
        if (forced.isNotEmpty()) {
            val sameLang = forced.firstOrNull { audioLanguage != null && normalize(subtitles[it].language) == audioLanguage }
            val eng = forced.firstOrNull { isEnglish(subtitles[it].language) }
            val unknown = forced.firstOrNull { normalize(subtitles[it].language) == null }
            (sameLang ?: eng ?: unknown)?.let { return it }
        }
        if (audioLanguage != null && audioLanguage != "en") {
            val english = subtitles.indices.filter { isEnglish(subtitles[it].language) && !subtitles[it].isForced }
            if (english.isNotEmpty()) {
                // Prefer full subs over SDH, then the default-flagged one.
                return english.sortedWith(
                    compareBy<Int>({ subtitles[it].isHearingImpaired }, { !subtitles[it].isDefault }),
                ).first()
            }
        }
        return null
    }

    fun isEnglish(language: String?): Boolean = normalize(language) == "en"

    private val aliases = mapOf(
        "eng" to "en", "english" to "en",
        "ger" to "de", "deu" to "de", "german" to "de", "deutsch" to "de",
        "fre" to "fr", "fra" to "fr", "french" to "fr",
        "spa" to "es", "spanish" to "es", "esl" to "es",
        "ita" to "it", "italian" to "it",
        "jpn" to "ja", "japanese" to "ja",
        "kor" to "ko", "korean" to "ko",
        "chi" to "zh", "zho" to "zh", "chinese" to "zh",
        "por" to "pt", "portuguese" to "pt",
        "rus" to "ru", "russian" to "ru",
        "dut" to "nl", "nld" to "nl", "dutch" to "nl",
        "swe" to "sv", "swedish" to "sv",
        "nor" to "no", "nob" to "no", "norwegian" to "no",
        "dan" to "da", "danish" to "da",
        "fin" to "fi", "finnish" to "fi",
        "pol" to "pl", "polish" to "pl",
        "hin" to "hi", "hindi" to "hi",
        "ara" to "ar", "arabic" to "ar",
        "tur" to "tr", "turkish" to "tr",
    )

    /** "eng", "en-US", "English" → "en"; "und"/"unknown"/blank → null. */
    fun normalize(language: String?): String? {
        val raw = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val base = raw.substringBefore('-').substringBefore('_')
        if (base == "und" || base == "unknown" || base == "zxx" || base == "mul") return null
        return aliases[base] ?: if (base.length == 2) base else base
    }

    fun audio(tracks: List<TrackInfo>) = tracks.filter { it.type == TrackType.AUDIO }
    fun subtitles(tracks: List<TrackInfo>) = tracks.filter { it.type == TrackType.SUBTITLE }
}
