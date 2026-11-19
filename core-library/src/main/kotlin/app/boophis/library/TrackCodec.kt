// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import app.boophis.model.TrackInfo
import app.boophis.model.TrackType

/** Compact line/tab encoding of track lists for a single text column (no JSON dependency). */
internal object TrackCodec {
    fun encode(tracks: List<TrackInfo>): String = tracks.joinToString("\n") { t ->
        listOf(
            t.type.name, t.language, t.label, t.codec, t.channels?.toString(),
            if (t.isDefault) "1" else "0", if (t.isForced) "1" else "0", if (t.isHearingImpaired) "1" else "0",
            t.externalPath,
        ).joinToString("\t") { esc(it) }
    }

    fun decode(s: String): List<TrackInfo> {
        if (s.isEmpty()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val f = line.split('\t').map { unesc(it) }
            if (f.size < 9) return@mapNotNull null
            val type = runCatching { TrackType.valueOf(f[0]!!) }.getOrNull() ?: return@mapNotNull null
            TrackInfo(
                type = type, language = f[1], label = f[2], codec = f[3], channels = f[4]?.toIntOrNull(),
                isDefault = f[5] == "1", isForced = f[6] == "1", isHearingImpaired = f[7] == "1", externalPath = f[8],
            )
        }
    }

    // null -> "\u0000", with backslash escapes for tab/newline/backslash.
    private fun esc(v: String?): String =
        if (v == null) "\\0" else v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(v: String): String? {
        if (v == "\\0") return null
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                i++
                sb.append(when (v[i]) { 't' -> '\t'; 'n' -> '\n'; else -> v[i] })
            } else sb.append(c)
            i++
        }
        return sb.toString()
    }
}
