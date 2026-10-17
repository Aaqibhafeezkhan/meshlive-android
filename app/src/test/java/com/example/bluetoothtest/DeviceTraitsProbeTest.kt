package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** BACKLOG R-03: the probe reads through an injected source; every failed read is "unknown". */
class DeviceTraitsProbeTest {
    private open class FakeSource(
        private val properties: Map<String, String> = emptyMap(),
    ) : DeviceTraitSource {
        override fun systemProperty(name: String) = properties[name]
        override fun supportedAbis() = listOf("arm64-v8a", "armeabi-v7a")
        override fun pageSizeBytes(): Int? = 4096
        override fun totalMemBytes(): Long? = 8L * 1024 * 1024 * 1024
        override fun isLowRamDevice(): Boolean? = false
        override fun memoryClassMb(): Int? = 256
        override fun mediaPerformanceClass(): Int? = 33
        override fun packageVersionName(packageName: String) =
            if (packageName == "com.google.android.bluetooth") "341516000" else null
        override fun hasSystemFeature(name: String): Boolean? = name == "android.hardware.audio.low_latency"
        override fun outputSampleRateHz(): Int? = 48_000
        override fun aecAvailable(): Boolean? = true
        override fun nsAvailable(): Boolean? = false
        override fun fingerprint() = "samsung/e2sxxx/e2s:14/UP1A.231005.007/S911BXXU3BXA1:user/release-keys"
        override fun cameraIds() = listOf("0", "1", "2")
        override fun cameraHardwareLevel(id: String): Int? = when (id) {
            "0" -> 1 // FULL
            "1" -> 0 // LIMITED
            else -> 2 // LEGACY
        }
    }

    /** Every read fails, each with a different Throwable the real platform can produce. */
    private object Hostile : DeviceTraitSource {
        override fun systemProperty(name: String): String? = throw NoSuchMethodException(name)
        override fun supportedAbis(): List<String> = throw NullPointerException()
        override fun pageSizeBytes(): Int? = throw IllegalStateException()
        override fun totalMemBytes(): Long? = throw SecurityException()
        override fun isLowRamDevice(): Boolean? = throw AssertionError("vendor service")
        override fun memoryClassMb(): Int? = throw NoSuchMethodError("MEDIA_PERFORMANCE_CLASS")
        override fun mediaPerformanceClass(): Int? = throw RuntimeException()
        override fun packageVersionName(packageName: String): String? = throw IllegalArgumentException()
        override fun hasSystemFeature(name: String): Boolean? = throw UnsupportedOperationException()
        override fun outputSampleRateHz(): Int? = throw NumberFormatException()
        override fun aecAvailable(): Boolean? = throw LinkageError()
        override fun nsAvailable(): Boolean? = throw AssertionError()
        override fun fingerprint(): String? = throw NullPointerException()
        override fun cameraIds(): List<String> = throw IllegalStateException("camera service")
        override fun cameraHardwareLevel(id: String): Int? = throw AssertionError()
    }

    @Test
    fun aHealthyPhoneIsReadCompletely() {
        val traits = DeviceTraitsProbe.read(FakeSource(mapOf("ro.build.version.oneui" to "60100")))
        assertEquals(mapOf("ro.build.version.oneui" to "60100"), traits.romProperties)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), traits.supportedAbis)
        assertEquals(4096, traits.pageSizeBytes)
        assertEquals(8L * 1024 * 1024 * 1024, traits.totalMemBytes)
        assertEquals(false, traits.lowRamDevice)
        assertEquals(256, traits.memoryClassMb)
        assertEquals(33, traits.mediaPerformanceClass)
        assertEquals(mapOf("com.google.android.bluetooth" to "341516000"), traits.bluetoothPackageVersions)
        assertEquals(true, traits.audioLowLatency)
        assertEquals(false, traits.audioPro)
        assertEquals(48_000, traits.outputSampleRateHz)
        assertEquals(true, traits.aecAvailable)
        assertEquals(false, traits.nsAvailable)
        assertTrue(traits.fingerprint.startsWith("samsung/"))
        assertNull("camera levels are lazy", traits.cameraHardwareLevels)
    }

    @Test
    fun everyFailedReadBecomesUnknownAndNothingThrows() {
        val traits = DeviceTraitsProbe.read(Hostile)
        assertEquals(DeviceRuntimeTraits(), traits)
        val pairs = traits.describe().split(';')
        for (unknown in listOf("rom=none", "abis=?", "pageSize=?", "totalMemMb=?", "lowRam=?", "memClassMb=?", "mpc=?",
            "btPkg=?", "lowLatencyAudio=?", "proAudio=?", "outRateHz=?", "aec=?", "ns=?", "camLevels=unread", "fingerprint=?")) {
            assertTrue("$unknown in $pairs", unknown in pairs)
        }
        assertEquals(emptyMap<String, CameraHardwareLevel>(), DeviceTraitsProbe.readCameraLevels(Hostile))
    }

    @Test
    fun oneFailingReadDoesNotHideTheOthers() {
        val partly = object : FakeSource() {
            override fun pageSizeBytes(): Int? = throw AssertionError()
        }
        val traits = DeviceTraitsProbe.read(partly)
        assertNull(traits.pageSizeBytes)
        assertEquals(33, traits.mediaPerformanceClass)
        assertEquals(48_000, traits.outputSampleRateHz)
    }

    @Test
    fun nonsenseValuesAreUnknownNotZero() {
        val odd = object : FakeSource() {
            override fun pageSizeBytes(): Int? = 0
            override fun totalMemBytes(): Long? = -1L
            override fun memoryClassMb(): Int? = 0
            override fun outputSampleRateHz(): Int? = 0
        }
        val traits = DeviceTraitsProbe.read(odd)
        assertNull(traits.pageSizeBytes)
        assertNull(traits.totalMemBytes)
        assertNull(traits.memoryClassMb)
        assertNull(traits.outputSampleRateHz)
    }

    @Test
    fun aVirtualMachineErrorIsNotSwallowed() {
        val dying = object : FakeSource() {
            override fun memoryClassMb(): Int? = throw OutOfMemoryError()
        }
        assertThrows(OutOfMemoryError::class.java) { DeviceTraitsProbe.read(dying) }
    }

    @Test
    fun romPropertiesAreLabelledUnverifiedAndBlankValuesAreDropped() {
        val traits = DeviceTraitsProbe.read(
            FakeSource(mapOf("ro.miui.ui.version.name" to " V816 ", "ro.build.version.emui" to "  ", "ro.not.listed" to "x")),
        )
        assertEquals(mapOf("ro.miui.ui.version.name" to "V816"), traits.romProperties)
        assertTrue(traits.describe(), "rom=unverified{ro.miui.ui.version.name=V816}" in traits.describe().split(';'))
        assertTrue("ro.tranos.version" in DeviceTraitsProbe.ROM_PROPERTIES)
        assertEquals(DeviceTraitsProbe.ROM_PROPERTIES.size, DeviceTraitsProbe.ROM_PROPERTIES.toSet().size)
    }

    @Test
    fun cameraLevelsAreNamesNeverOrderedNumbers() {
        val levels = DeviceTraitsProbe.readCameraLevels(FakeSource())
        assertEquals(
            mapOf("0" to CameraHardwareLevel.FULL, "1" to CameraHardwareLevel.LIMITED, "2" to CameraHardwareLevel.LEGACY),
            levels,
        )
        assertEquals(CameraHardwareLevel.UNKNOWN, CameraHardwareLevel.fromCamera2(null))
        assertEquals(CameraHardwareLevel.UNKNOWN, CameraHardwareLevel.fromCamera2(99))
        val withCameras = DeviceRuntimeTraits(cameraHardwareLevels = levels)
        assertTrue(withCameras.describe().split(';').contains("camLevels=0:FULL+1:LIMITED+2:LEGACY"))
    }

    @Test
    fun aCameraWhoseCharacteristicsFailIsUnknownButTheRestAreRead() {
        val partly = object : FakeSource() {
            override fun cameraHardwareLevel(id: String): Int? = if (id == "1") throw AssertionError() else super.cameraHardwareLevel(id)
        }
        val levels = DeviceTraitsProbe.readCameraLevels(partly)
        assertEquals(CameraHardwareLevel.UNKNOWN, levels.getValue("1"))
        assertEquals(CameraHardwareLevel.FULL, levels.getValue("0"))
    }

    @Test
    fun runtimeMatcherNeverMatchesAnUnreadProbeOrUnknownFact() {
        val lowRam = RuntimeMatcher(lowRamDevice = true)
        assertFalse(lowRam.matches(null))
        assertFalse(lowRam.matches(DeviceRuntimeTraits()))
        assertTrue(lowRam.matches(DeviceRuntimeTraits(lowRamDevice = true)))
        assertFalse(lowRam.matches(DeviceRuntimeTraits(lowRamDevice = false)))

        val classAtMost = RuntimeMatcher(mediaPerformanceClassAtMost = 0)
        assertTrue("undeclared class is 0", classAtMost.matches(DeviceRuntimeTraits(mediaPerformanceClass = 0)))
        assertFalse(classAtMost.matches(DeviceRuntimeTraits(mediaPerformanceClass = 33)))
        assertFalse(classAtMost.matches(DeviceRuntimeTraits()))

        val page16k = RuntimeMatcher(pageSizeAtLeastBytes = 16384)
        assertTrue(page16k.matches(DeviceRuntimeTraits(pageSizeBytes = 16384)))
        assertFalse(page16k.matches(DeviceRuntimeTraits(pageSizeBytes = 4096)))
        assertFalse(page16k.matches(DeviceRuntimeTraits()))

        val rom = RuntimeMatcher(romPropertiesAny = setOf("ro.build.version.oneui", "ro.vivo.os.version"))
        assertTrue(rom.matches(DeviceRuntimeTraits(romProperties = mapOf("ro.vivo.os.version" to "14"))))
        assertFalse(rom.matches(DeviceRuntimeTraits(romProperties = mapOf("ro.build.version.emui" to "14"))))

        val legacyCamera = RuntimeMatcher(anyCameraLevelIn = setOf(CameraHardwareLevel.LEGACY))
        assertFalse("camera levels not read yet", legacyCamera.matches(DeviceRuntimeTraits()))
        assertTrue(
            legacyCamera.matches(
                DeviceRuntimeTraits(cameraHardwareLevels = mapOf("0" to CameraHardwareLevel.FULL, "1" to CameraHardwareLevel.LEGACY)),
            ),
        )
        assertFalse(legacyCamera.matches(DeviceRuntimeTraits(cameraHardwareLevels = mapOf("0" to CameraHardwareLevel.FULL))))
    }

    @Test
    fun aMatcherUsesTheRuntimeTraitsOfThePhone() {
        val matcher = DeviceMatcher(sdkMin = 33, runtime = RuntimeMatcher(audioLowLatency = true))
        val unread = DeviceTraits("qcom", "taro", 34)
        assertFalse(matcher.matches(unread))
        val read = unread.copy(runtime = DeviceRuntimeTraits(audioLowLatency = true))
        assertTrue(matcher.matches(read))
        assertFalse(matcher.matches(read.copy(runtime = DeviceRuntimeTraits(audioLowLatency = false))))
        assertFalse(matcher.universal)
        assertTrue(matcher.describe().contains("lowLatencyAudio=true"))
    }

    @Test
    fun theDevicePolicyLineShowsRuntimeFactsAsFlatPairs() {
        val unread = DeviceTraits("qcom", "taro", 34).describe().split(';')
        assertTrue("runtime=unread" in unread)
        val traits = DeviceTraits("qcom", "taro", 34, runtime = DeviceTraitsProbe.read(FakeSource()))
        val line = traits.describe()
        assertFalse(line.contains("|"))
        val pairs = line.split(';')
        assertTrue(pairs.toString(), "pageSize=4096" in pairs && "mpc=33" in pairs && "aec=1" in pairs)
        assertTrue(pairs.any { it.startsWith("fingerprint=samsung/") })
    }

    @Test
    fun fingerprintAndRomCannotBreakTheLineFormat() {
        val traits = DeviceRuntimeTraits(fingerprint = "a;b|c d", romProperties = mapOf("ro.vivo.os.version" to "x;y|z"))
        val line = traits.describe()
        assertFalse(line.contains("|"))
        assertEquals(1, line.split(';').count { it.startsWith("fingerprint=") })
        assertTrue("fingerprint=a_b_c_d" in line.split(';'))
    }
}
