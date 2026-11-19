// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

/** Pure-Kotlin filename / folder-name parsing (Jellyfin/Kodi conventions). No android.* imports. */
object Naming {
    val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "mpg", "mpeg")
    val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "vtt", "sub")
    val IMAGE_EXT = listOf("jpg", "jpeg", "png", "webp")

    private val EXTRAS_DIRS = setOf("extras", "featurettes", "behind the scenes", "trailers", "sample", "samples")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun baseName(name: String): String = if ('.' in name) name.substringBeforeLast('.') else name
    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXT

    fun isExtrasDir(name: String): Boolean = name.trim().lowercase() in EXTRAS_DIRS

    private val SKIP_VIDEO = Regex("""(?i)^(sample|trailer)$|[-._ ](sample|trailer)$|^sample[-._ ]""")
    fun isSkippableVideo(base: String): Boolean = SKIP_VIDEO.containsMatchIn(base.trim())

    private val PART = Regex("""(?i)^(.*?)[\s._-]+(?:cd|part|pt|disc|disk)[\s._-]*(\d{1,2})$""")

    /** For "Movie (2020)-cd2" returns ("Movie (2020)", 2); null when the name has no part marker. */
    fun partOf(base: String): Pair<String, Int>? =
        PART.matchEntire(base.trim())?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    // ---- titles ---------------------------------------------------------------------------

    data class ParsedTitle(val title: String, val year: Int?)

    private val JUNK = Regex(
        """(?<![A-Za-z0-9])(?:480p|576p|720p|1080p|1080i|2160p|4k|blu-?ray|bdrip|brrip|web-?dl|webrip|web|hdtv|dvdrip|""" +
            """remux|hdr10?|dv|uhd|x264|x265|h\.?264|h\.?265|hevc|av1|xvid|aac|ac3|eac3|dts(?:-hd)?|truehd|atmos|""" +
            """ddp?\d(?:\.\d)?|5\.1|7\.1|10bit)(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val PAREN_YEAR = Regex("""[(\[]\s*((?:19|20)\d\d)\s*[)\]]""")
    private val BARE_YEAR = Regex("""(?<=[\s._-])((?:19|20)\d\d)(?![0-9A-Za-z])""")
    private val BRACKET_TAG = Regex("""\[[^\]]*]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Parses `Title (Year)`, `Title [Year]` and `Title.Year.Junk-GRP` into a clean title and year. */
    fun parseTitle(raw: String): ParsedTitle {
        val s = raw.trim()
        val paren = PAREN_YEAR.findAll(s).lastOrNull { it.range.first > 0 && s.substring(0, it.range.first).isNotBlank() }
        if (paren != null) {
            val t = clean(s.substring(0, paren.range.first))
            return ParsedTitle(t.ifBlank { clean(s) }, paren.groupValues[1].toInt())
        }
        val junkIdx = JUNK.findAll(s).firstOrNull { it.range.first > 0 }?.range?.first
        val year = BARE_YEAR.findAll(s).lastOrNull {
            s.substring(0, it.range.first).isNotBlank() && (junkIdx == null || it.range.first < junkIdx)
        }
        if (year != null) {
            val t = clean(s.substring(0, year.range.first))
            if (t.isNotBlank()) return ParsedTitle(t, year.groupValues[1].toInt())
        }
        val cut = if (junkIdx != null) s.substring(0, junkIdx) else s
        return ParsedTitle(clean(cut).ifBlank { clean(s) }.ifBlank { s }, null)
    }

    private fun clean(s: String): String {
        var t = BRACKET_TAG.replace(s, " ")
        t = t.replace('_', ' ')
        if (' ' !in t.trim()) t = t.replace('.', ' ')
        return WHITESPACE.replace(t, " ").trim(' ', '-', '.', '_', '(', ')')
    }

    private val LEADING_ARTICLE = Regex("""(?i)^(?:the|an|a)\s+(?=\S)""")
    fun sortTitle(title: String): String = LEADING_ARTICLE.replaceFirst(title.trim(), "").ifBlank { title }

    // ---- episodes -------------------------------------------------------------------------

    data class ParsedEpisode(val season: Int?, val episode: Int, val episodeEnd: Int?, val title: String?)

    private val SXXEYY = Regex(
        """(?i)(?<![A-Za-z0-9])S(\d{1,2})[ ._-]?E(\d{1,3})(?:-?E(\d{1,3})|-(\d{1,3})(?![0-9pPiI]))?""",
    )
    private val NXMM = Regex("""(?i)(?<![A-Za-z0-9])(\d{1,2})x(\d{2,3})(?![0-9pPiI])""")
    private val EONLY = Regex("""(?i)(?<![A-Za-z0-9])E(\d{1,3})(?![0-9A-Za-z])""")
    private val LEADING_NUM = Regex("""^(\d{1,3})(?=[\s._-])""")

    /**
     * Parses an episode file base name. [folderSeason] is used when the name carries no season.
     * Returns null when no episode number can be found.
     */
    fun parseEpisode(base: String, folderSeason: Int? = null): ParsedEpisode? {
        SXXEYY.find(base)?.let { m ->
            val end = (m.groupValues[3].ifEmpty { m.groupValues[4] }).toIntOrNull()
            return ParsedEpisode(m.groupValues[1].toInt(), m.groupValues[2].toInt(), end, episodeTitle(base, m.range.last + 1))
        }
        NXMM.find(base)?.let { m ->
            return ParsedEpisode(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, episodeTitle(base, m.range.last + 1))
        }
        EONLY.find(base)?.let { m ->
            return ParsedEpisode(folderSeason, m.groupValues[1].toInt(), null, episodeTitle(base, m.range.last + 1))
        }
        if (folderSeason != null) {
            LEADING_NUM.find(base)?.let { m ->
                return ParsedEpisode(folderSeason, m.groupValues[1].toInt(), null, episodeTitle(base, m.range.last + 1))
            }
        }
        return null
    }

    private fun episodeTitle(base: String, from: Int): String? {
        var rest = base.substring(from).trimStart(' ', '.', '_', '-')
        val junk = JUNK.find(rest)
        if (junk != null) rest = rest.substring(0, junk.range.first)
        return clean(rest).ifBlank { null }
    }

    private val SEASON_DIR = Regex("""(?i)^(?:season|series|staffel|saison|temporada)[\s._-]*(\d{1,3})(?!\d)""")
    private val SHORT_SEASON_DIR = Regex("""(?i)^s(\d{1,2})$""")
    private val SPECIALS_DIR = Regex("""(?i)^(?:specials?|season[\s._-]*specials?)$""")

    /** "Season 02" -> 2, "Specials" -> 0, anything else null. */
    fun seasonFromFolder(name: String): Int? {
        val n = name.trim()
        if (SPECIALS_DIR.matches(n)) return 0
        SEASON_DIR.find(n)?.let { return it.groupValues[1].toInt() }
        SHORT_SEASON_DIR.find(n)?.let { return it.groupValues[1].toInt() }
        return null
    }
}
