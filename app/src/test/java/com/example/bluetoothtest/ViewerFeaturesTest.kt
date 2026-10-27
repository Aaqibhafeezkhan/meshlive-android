package com.example.bluetoothtest

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

/**
 * The viewer frame-age switch follows the shared feature rules (FeatureCatalog): one default
 * constant, OFF until phone test T11 passes; a benchmark launch reads only `test_viewer_frame_age`;
 * everything it draws is hidden in the layout, so with the switch off the Watch screen is unchanged.
 */
class ViewerFeaturesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Before
    fun clean() = FeatureCatalog.clearForTest()

    @After
    fun cleanUp() = FeatureCatalog.clearForTest()

    @Test
    fun theFeatureIsOffByDefaultAndKeepsItsBenchmarkSwitch() {
        assertFalse(ViewerFeatureDefaults.FRAME_AGE)
        val spec = ViewerFeatures.frameAge
        assertEquals(ViewerFeatureDefaults.FRAME_AGE, spec.defaultEnabled)
        assertEquals("test_viewer_frame_age", spec.benchmarkExtra)
        assertEquals("feature_viewer_frame_age", spec.prefKey)
        assertEquals("viewer_frame_age", spec.id)
        assertEquals(ViewerFeatures.GROUP, spec.group)
        assertEquals("T11", spec.phoneTest)
    }

    @Test
    fun registeringItNextToTheOtherFeaturesCollidesWithNone() {
        CallFeatureSpecs.registerAll()
        ConnectivityFeatures.registerAll()
        ViewerFeatures.registerAll()
        ViewerFeatures.registerAll()
        assertEquals(
            // + 1: rotating IDs register with the connectivity features (Find PCs and pseudonyms depend on them).
            CallFeatureSpecs.all.size + ConnectivityFeatures.all.size + 1 + ViewerFeatures.all.size,
            FeatureCatalog.all().size,
        )
        assertNotNull(FeatureCatalog.spec("viewer_frame_age"))
    }

    @Test
    fun aBenchmarkLaunchReadsOnlyItsExtraAndNeverAStoredChoiceOrTheDefault() {
        val extra = ViewerFrameAgeSettings.EXTRA_ENABLED
        assertTrue(ViewerFrameAgeSettings.resolve(true, { it == extra }, { null }))
        assertFalse(ViewerFrameAgeSettings.resolve(true, { false }, { true }))
        // Flipping the shipped default must not reach a measured case.
        val flipped = ViewerFeatures.frameAge.copy(defaultEnabled = true)
        assertFalse(FeatureCatalog.resolve(flipped, true, { false }, { null }))
    }

    @Test
    fun anOrdinaryLaunchFollowsTheDefaultUntilTheUserChooses() {
        assertFalse(ViewerFrameAgeSettings.resolve(false, { true }, { null }))
        assertTrue(
            ViewerFrameAgeSettings.resolve(false, { false }, { if (it == ViewerFrameAgeSettings.PREF_ENABLED) true else null }),
        )
        val flipped = ViewerFeatures.frameAge.copy(defaultEnabled = true)
        assertTrue(FeatureCatalog.resolve(flipped, false, { false }, { null }))
        assertFalse(FeatureCatalog.resolve(flipped, false, { false }, { false }))
    }

    @Test
    fun everythingTheFeatureDrawsStartsHiddenInTheLayout() {
        val file = listOf("src/main/res/layout/activity_main.xml", "app/src/main/res/layout/activity_main.xml")
            .map(::File).first { it.isFile }
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
        for (id in listOf("viewerStaleScrim", "viewerStaleBadge", "viewerFrameAgeLabel", "viewerStaleNote")) {
            val view = root.descendants().firstOrNull { it.getAttributeNS(androidNs, "id") == "@+id/$id" }
            assertNotNull("missing $id", view)
            assertEquals("$id must start gone", "gone", view!!.getAttributeNS(androidNs, "visibility"))
        }
    }

    private fun Element.descendants(): Sequence<Element> = sequence {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            yield(child)
            yieldAll(child.descendants())
        }
    }
}
