// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import org.xml.sax.InputSource

/** Tolerant Kodi/Jellyfin NFO parser. Pure Kotlin (javax.xml + a tolerant fallback), no android.* imports. */
object NfoParser {
    data class Nfo(
        val root: String,
        val title: String? = null,
        val originalTitle: String? = null,
        val sortTitle: String? = null,
        val year: Int? = null,
        val plot: String? = null,
        val rating: Float? = null,
        val mpaa: String? = null,
        val genres: List<String> = emptyList(),
        val runtimeMinutes: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val aired: String? = null,
        val audioLanguages: List<String> = emptyList(),
        val subtitleLanguages: List<String> = emptyList(),
    )

    /** Minimal element tree shared by the DOM path and the tolerant fallback scanner. */
    internal class Node(val name: String, val attrs: Map<String, String> = emptyMap()) {
        val children = ArrayList<Node>()
        val text = StringBuilder()
        fun child(n: String): Node? = children.firstOrNull { it.name.equals(n, true) }
        fun kids(n: String): List<Node> = children.filter { it.name.equals(n, true) }
        fun str(n: String): String? = child(n)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun parseFile(file: File): Nfo? = try {
        parse(decode(file.readBytes()))
    } catch (_: Exception) {
        null
    }

    private fun decode(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else -> String(b, Charsets.UTF_8)
    }

    private val ROOT_TAG = Regex("""<(?![?!])([A-Za-z_][\w:.-]*)""")

    /** Returns null when no element can be recovered at all. */
    fun parse(input: String): Nfo? {
        var s = input.replace("﻿", "")
        val lt = s.indexOf('<')
        if (lt < 0) return null
        s = s.substring(lt)
        // Cut trailing junk (e.g. a URL line after the XML) after the first closing root tag.
        val rootName = ROOT_TAG.find(s)?.groupValues?.get(1)
        val xml = if (rootName != null) {
            val close = s.indexOf("</$rootName>")
            if (close >= 0) s.substring(0, close + rootName.length + 3) else s
        } else s
        val tree = domTree(xml) ?: tolerantTree(xml) ?: return null
        return extract(tree)
    }

    private fun domTree(xml: String): Node? = try {
        val f = DocumentBuilderFactory.newInstance()
        for ((k, v) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            try { f.setFeature(k, v) } catch (_: Exception) { }
        }
        val b = f.newDocumentBuilder()
        b.setErrorHandler(null)
        val doc = b.parse(InputSource(StringReader(xml.trim())))
        fromDom(doc.documentElement)
    } catch (_: Throwable) {
        null
    }

    private fun fromDom(e: Element): Node {
        val attrs = HashMap<String, String>()
        val a = e.attributes
        for (i in 0 until a.length) attrs[a.item(i).nodeName.lowercase()] = a.item(i).nodeValue
        val n = Node(e.tagName, attrs)
        var c = e.firstChild
        while (c != null) {
            when (c.nodeType) {
                DomNode.ELEMENT_NODE -> n.children.add(fromDom(c as Element))
                DomNode.TEXT_NODE, DomNode.CDATA_SECTION_NODE -> n.text.append(c.nodeValue)
            }
            c = c.nextSibling
        }
        return n
    }

    private val TAG = Regex("""<(/?)([A-Za-z_][\w:.-]*)([^<>]*?)(/?)>|<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val PI = Regex("""<\?.*?\?>""", RegexOption.DOT_MATCHES_ALL)
    private val DOCTYPE = Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE)

    private fun tolerantTree(xml: String): Node? {
        val s = DOCTYPE.replace(PI.replace(COMMENT.replace(xml, ""), ""), "")
        val stack = ArrayList<Node>()
        var root: Node? = null
        var pos = 0
        fun addText(t: String, raw: Boolean) {
            stack.lastOrNull()?.text?.append(if (raw) t else unescape(t))
        }
        for (m in TAG.findAll(s)) {
            addText(s.substring(pos, m.range.first), false)
            pos = m.range.last + 1
            if (m.groups[5] != null) { addText(m.groupValues[5], true); continue }
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            if (closing) {
                val idx = stack.indexOfLast { it.name.equals(name, true) }
                if (idx >= 0) {
                    while (stack.size > idx) stack.removeAt(stack.size - 1)
                    if (stack.isEmpty()) break
                }
            } else {
                val attrs = ATTR.findAll(m.groupValues[3]).associate {
                    it.groupValues[1].lowercase() to unescape(it.groupValues[2].ifEmpty { it.groupValues[3] })
                }
                // An unclosed text field cannot contain another element: close it implicitly.
                while (stack.isNotEmpty() && stack.last().name.lowercase() in LEAF_FIELDS) stack.removeAt(stack.size - 1)
                val n = Node(name, attrs)
                if (root == null) root = n else (stack.lastOrNull()?.children?.add(n) ?: break)
                if (m.groupValues[4] != "/") stack.add(n)
            }
        }
        return root
    }

    private val LEAF_FIELDS = setOf(
        "title", "originaltitle", "sorttitle", "year", "plot", "outline", "mpaa", "genre", "runtime", "season",
        "episode", "aired", "premiered", "releasedate", "value", "votes", "language", "codec", "durationinseconds",
    )

    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""")

    private fun unescape(t: String): String {
        if ('&' !in t) return t
        return ENTITY.replace(t) { m ->
            when (val g = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val cp = if (g.startsWith("#x")) g.substring(2).toIntOrNull(16) else g.substring(1).toIntOrNull()
                    cp?.let { String(Character.toChars(it)) } ?: m.value
                }
            }
        }
    }

    private val DIGITS = Regex("""\d+""")
    private val YEAR4 = Regex("""(?<!\d)(\d{4})(?!\d)""")

    private fun leadingInt(s: String?): Int? = s?.let { DIGITS.find(it)?.value?.toIntOrNull() }
    private fun year(s: String?): Int? = s?.let { YEAR4.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    private fun float(s: String?): Float? = s?.trim()?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun extract(n: Node): Nfo {
        val inRatings = n.child("ratings")?.kids("rating")?.let { rs ->
            rs.firstOrNull { it.attrs["default"].equals("true", true) } ?: rs.firstOrNull()
        }
        val rating = float(inRatings?.str("value") ?: inRatings?.text?.toString())
            ?: n.kids("rating").firstNotNullOfOrNull { float(it.str("value") ?: it.text.toString()) }
        val details = n.child("fileinfo")?.child("streamdetails")
        val runtime = leadingInt(n.str("runtime"))
            ?: leadingInt(details?.child("video")?.str("durationinseconds"))?.let { (it + 30) / 60 }
        return Nfo(
            root = n.name,
            title = n.str("title"),
            originalTitle = n.str("originaltitle"),
            sortTitle = n.str("sorttitle"),
            year = year(n.str("year")) ?: year(n.str("premiered")) ?: year(n.str("aired")) ?: year(n.str("releasedate")),
            plot = n.str("plot") ?: n.str("outline"),
            rating = rating,
            mpaa = n.str("mpaa"),
            genres = n.kids("genre").mapNotNull { it.text.toString().trim().ifEmpty { null } },
            runtimeMinutes = runtime?.takeIf { it > 0 },
            season = leadingInt(n.str("season")),
            episode = leadingInt(n.str("episode")),
            aired = n.str("aired") ?: n.str("premiered"),
            audioLanguages = details?.kids("audio")?.mapNotNull { it.str("language") } ?: emptyList(),
            subtitleLanguages = details?.kids("subtitle")?.mapNotNull { it.str("language") } ?: emptyList(),
        )
    }
}
