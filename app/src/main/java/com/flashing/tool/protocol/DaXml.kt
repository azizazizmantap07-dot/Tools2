package com.flashing.tool.protocol

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Builds and parses the XML command envelopes used by MTK DA V6.
 *
 * Envelope shape (confirmed from literal strings in DAMT6789.bin):
 *
 *   <?xml version="1.0" encoding="utf-8"?>
 *   <host><version>1.0</version><command>CMD:XXX</command>
 *     <arg><field>value</field>...</arg>
 *   </host>
 *
 * The DA replies with the same shape (root tag may differ, e.g.
 * <partition_table> for CMD:READ-PARTITION-TABLE), so parsing is kept
 * generic: callers ask for specific tag values by path rather than this
 * class assuming one fixed response shape.
 */
object DaXml {

    /**
     * Builds a `<host>` command envelope. [args] becomes nested `<arg>` children
     * in insertion order — order matters for some commands per the DA's own
     * "Required XML node path not found" validation, so use LinkedHashMap-backed
     * maps (or Kotlin's default mapOf, which preserves insertion order) when
     * calling this.
     */
    fun buildCommand(command: String, args: Map<String, String> = emptyMap()): ByteArray {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<da><version>1.0</version><command>")
        sb.append(command)
        sb.append("</command>")
        if (args.isNotEmpty()) {
            sb.append("<arg>")
            for ((key, value) in args) {
                sb.append('<').append(key).append('>')
                sb.append(escapeXml(value))
                sb.append("</").append(key).append('>')
            }
            sb.append("</arg>")
        }
        sb.append("</da>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * Parses an XML response into a flat map of tag-path -> text content, e.g.
     * "arg/result" -> "SUCCESSED". Sufficient for the shallow, non-repeating
     * envelopes (CMD:END, CMD:GET-DA-INFO, etc). For repeating elements like
     * partition table rows, use [parsePartitionTable] instead.
     */
    fun parseFlat(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val pathStack = ArrayDeque<String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> pathStack.addLast(parser.name)
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty() && pathStack.isNotEmpty()) {
                        result[pathStack.joinToString("/")] = text
                    }
                }
                XmlPullParser.END_TAG -> if (pathStack.isNotEmpty()) pathStack.removeLast()
            }
            eventType = parser.next()
        }
        return result
    }

    /**
     * Parses a CMD:READ-PARTITION-TABLE response:
     *   <partition_table version="1.0">
     *     <pt><name>boot</name><start>0x...</start><size>0x...</size></pt>
     *     ...
     *   </partition_table>
     */
    fun parsePartitionTable(xml: String): List<Triple<String, Long, Long>> {
        val entries = mutableListOf<Triple<String, Long, Long>>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var name: String? = null
        var start: Long? = null
        var size: Long? = null
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "pt") { name = null; start = null; size = null }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrEmpty()) {
                        when (currentTag) {
                            "name" -> name = text
                            "start" -> start = parseHex(text)
                            "size" -> size = parseHex(text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "pt" && name != null && start != null && size != null) {
                        entries += Triple(name!!, start!!, size!!)
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return entries
    }

    private fun parseHex(value: String): Long? = try {
        if (value.startsWith("0x", ignoreCase = true)) {
            java.lang.Long.parseUnsignedLong(value.substring(2), 16)
        } else {
            value.toLong()
        }
    } catch (_: NumberFormatException) {
        null
    }
}
