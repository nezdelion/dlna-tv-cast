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
    fun parse(text: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Device descriptions come from the network: refuse DTDs and external entities.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        return builder.parse(InputSource(StringReader(text.trimStart('﻿', ' ', '\r', '\n', '\t'))))
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

/** Depth-first search for elements with the given local name. */
internal fun Element.descendants(name: String): List<Element> {
    val result = mutableListOf<Element>()
    fun walk(e: Element) {
        for (c in e.children()) {
            if (c.localTag == name) result += c
            walk(c)
        }
    }
    walk(this)
    return result
}
