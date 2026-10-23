package io.github.nezdelion.dlnatvcast.core.upnp

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Small DOM helpers. `javax.xml.parsers` is available both on the JVM and on Android,
 * so `core` needs no parser abstraction.
 */
internal object Xml {
    const val MAX_DEPTH = 32
    const val MAX_ELEMENTS = 5_000

    fun parse(text: String): Document {
        val trimmed = text.trimStart('﻿', ' ', '\r', '\n', '\t')
        checkStructure(trimmed)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Defence in depth only: Android's factory ignores these, checkStructure is what refuses DTDs.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        return builder.parse(InputSource(StringReader(trimmed)))
    }

    /**
     * Parser-independent gate for XML from the network: refuses DOCTYPE/DTD declarations
     * (so no custom or external entities can exist) and documents deeper than [MAX_DEPTH]
     * or with more than [MAX_ELEMENTS] elements.
     *
     * @throws IllegalArgumentException when the document is refused.
     */
    fun checkStructure(text: String) {
        var i = 0
        var depth = 0
        var elements = 0
        fun skipPast(marker: String, from: Int): Int {
            val end = text.indexOf(marker, from)
            require(end >= 0) { "Unterminated markup" }
            return end + marker.length
        }
        while (true) {
            val lt = text.indexOf('<', i)
            if (lt < 0) return
            when {
                text.startsWith("<!--", lt) -> i = skipPast("-->", lt + 4)
                text.startsWith("<![CDATA[", lt) -> i = skipPast("]]>", lt + 9)
                text.startsWith("<!", lt) -> throw IllegalArgumentException("DOCTYPE and DTD declarations are not allowed")
                text.startsWith("<?", lt) -> i = skipPast("?>", lt + 2)
                text.startsWith("</", lt) -> {
                    depth--
                    i = skipPast(">", lt + 2)
                }
                else -> {
                    var j = lt + 1
                    var quote = 0.toChar()
                    while (j < text.length) {
                        val c = text[j]
                        if (quote != 0.toChar()) {
                            if (c == quote) quote = 0.toChar()
                        } else if (c == '"' || c == '\'') {
                            quote = c
                        } else if (c == '>') {
                            break
                        }
                        j++
                    }
                    require(j < text.length) { "Unterminated tag" }
                    require(++elements <= MAX_ELEMENTS) { "Too many XML elements" }
                    if (text[j - 1] != '/') {
                        require(++depth <= MAX_DEPTH) { "XML nested too deeply" }
                    }
                    i = j + 1
                }
            }
        }
    }

    fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

internal val Element.localTag: String get() = localName ?: tagName.substringAfter(':')

internal fun Element.children(): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

internal fun Element.child(name: String): Element? = children().firstOrNull { it.localTag == name }

internal fun Element.childText(name: String): String? = child(name)?.textContent?.trim()

/** Document-order search for elements with the given local name (iterative, no recursion). */
internal fun Element.descendants(name: String): List<Element> {
    val result = mutableListOf<Element>()
    val stack = ArrayDeque(children().asReversed())
    while (stack.isNotEmpty()) {
        val e = stack.removeLast()
        if (e.localTag == name) result += e
        e.children().asReversed().forEach { stack.addLast(it) }
    }
    return result
}
