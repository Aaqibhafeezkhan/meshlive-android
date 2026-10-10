package com.example.bluetoothtest

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Regression for the M31 home screen (font scale 1.1): Call and Push-to-talk keys stretched to
 * two thirds of the screen because they were match_parent with a weighted spacer inside a
 * fill-viewport ScrollView, pushing Go live and Watch below the fold. The keys must size to their
 * content (wrap_content plus a minimum height) and contain no weighted children.
 */
class HomeLayoutTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun layout(): Element {
        val file = listOf("src/main/res/layout/activity_main.xml", "app/src/main/res/layout/activity_main.xml")
            .map(::File).first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.descendants(): Sequence<Element> = sequence {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            yield(child)
            yieldAll(child.descendants())
        }
    }

    private fun byId(root: Element, id: String): Element =
        root.descendants().first { it.getAttributeNS(androidNs, "id") == "@+id/$id" }

    @Test
    fun homeKeysSizeToTheirContent() {
        val root = layout()
        listOf("privateCallButton", "streamModeButton", "receiveModeButton").forEach { id ->
            val key = byId(root, id)
            assertEquals("$id height", "wrap_content", key.getAttributeNS(androidNs, "layout_height"))
            key.descendants().forEach { child ->
                assertTrue(
                    "$id has a weighted child, which stretches inside a ScrollView",
                    child.getAttributeNS(androidNs, "layout_weight").isEmpty(),
                )
                assertTrue(
                    "$id has a match_parent-height child",
                    child.getAttributeNS(androidNs, "layout_height") != "match_parent",
                )
            }
        }
    }

    @Test
    fun actionsSitInTheThumbZoneBelowAFlexibleGap() {
        val root = layout()
        val home = byId(root, "homeScreen")
        val column = home.descendants().first()
        val children = (0 until column.childNodes.length).mapNotNull { column.childNodes.item(it) as? Element }
        val gap = children.indexOfFirst { it.getAttributeNS(androidNs, "layout_weight").isNotEmpty() && it.tagName == "View" }
        assertTrue("home needs one flexible gap above the actions", gap >= 0)
        listOf("receiveModeButton", "streamModeButton", "privateCallButton").forEach { id ->
            val index = children.indexOfFirst { it.getAttributeNS(androidNs, "id") == "@+id/$id" }
            assertTrue("$id must be below the gap (thumb zone)", index > gap)
        }
        // Push-to-talk is a call feature (walkie-talkie), not a Home action.
        assertEquals("gone", byId(root, "homePushToTalkButton").getAttributeNS(androidNs, "visibility"))
        // The old status line stays for setStatus but is never shown on Home.
        assertEquals("gone", byId(root, "statusText").getAttributeNS(androidNs, "visibility"))
    }

    @Test
    fun compactTopFitsWithAllActionsOnASmallPhone() {
        // 6.4" phone (1080 x 2340 at 420 dpi = 891 dp tall) minus status and navigation bars
        // (about 72 dp) leaves about 819 dp. Heights below are at font scale 1.3: title 48, status
        // line on two lines 48, ID 48, Recent header 40 + HOME_RECENT_ROWS rows of ~85, gap 24,
        // two action rows of ~90, call caption 50, Call button 72, paddings 24.
        val estimated = 48 + 48 + 48 + 40 + HOME_RECENT_ROWS * 85 + 24 + 2 * 90 + 50 + 72 + 24
        assertTrue("home needs $estimated dp", estimated <= 800)
    }
}
