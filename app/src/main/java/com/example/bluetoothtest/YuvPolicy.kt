package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.GOOGLE_OPEN_SOURCE
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL

/**
 * YUV policy (Image.Plane strides, pixel stride, crop, colour formats). Owner: codec workstream.
 *
 * Entries here join DevicePolicyRegistry.entries (one registry). Use DevicePolicyRegistry matchers
 * and constants freely, but never DevicePolicyRegistry.entries (initialisation order).
 */
internal object YuvPolicy {
    private val IMAGE_CROP_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/Image#getCropRect()",
        "only the pixels inside the crop rectangle are valid; it defaults to the whole image",
    )
    private val PLANE_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/Image.Plane",
        "YUV_420_888: plane 0 Y, 1 U (Cb), 2 V (Cr); each plane has its own row and pixel stride; U and V " +
            "share row and pixel stride, and a pixel stride of 2 means interleaved chroma",
    )
    private val LIBYUV_ANDROID420 = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://chromium.googlesource.com/libyuv/libyuv/+/eeb06a6451ba2e21f8ff272c7b42fc62b9faecc5/include/libyuv/convert.h#915",
        "Android420ToI420(..., src_pixel_stride_uv, ...): pixel stride 1 planar, 2 semi-planar (NV12/NV21 " +
            "detected from the U/V address order), anything else per-sample; chroma (n + 1) >> 1",
    )

    /** Image.getCropRect is the valid region of a camera frame (every phone). */
    val IMAGE_CROP_RECT = DevicePolicyEntry(
        id = "yuv-image-crop-rect",
        area = PolicyArea.YUV,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Camera YUV copies start from Image.getCropRect()",
        matcher = DeviceMatcher(),
        effects = listOf(HonourImageCropRect),
        behaviour = "The camera-to-encoder YUV copies (Kotlin and native) take the frame's crop rectangle (left/top " +
            "rounded down to even) as the source area before the centre crop; with the default full-image crop " +
            "nothing changes.",
        sources = listOf(IMAGE_CROP_DOC, PLANE_DOC, LIBYUV_ANDROID420),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no local camera reported a non-default crop; synthetic YuvPlaneCopyTest"),
        confidence = MEDIUM,
        confidenceNote = "documented API contract; whether any of our phones' camera HALs set a crop is unmeasured",
        reviewed = "2026-10-10",
        origin = "Compatibility audit 2026-10-10 (copies used Image.width/height and ignored getCropRect)",
        fallback = "Off: the whole image is the source area, as before.",
    )

    val entries: List<DevicePolicyEntry> = listOf(IMAGE_CROP_RECT)

    fun cropRectHonoured(traits: DeviceTraits): Boolean = DeviceFamilyPolicy.resolve(traits).has<HonourImageCropRect>()
}

internal data object HonourImageCropRect : DevicePolicyEffect {
    override fun describe(): String = "YUV copies start from Image.getCropRect()"
}
