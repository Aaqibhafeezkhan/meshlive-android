package com.example.bluetoothtest

/**
 * Pure decisions that keep a DEBUG raw I420 fixture (`test_raw_yuv`) the only video source of a launch.
 * Lane 11 (build ae00937): eight thinning runs of an ASUS source with `test_raw_yuv` and temporal layers were
 * called "raw ignored, live camera used" because the log said "Send camera" and had no `BENCHMARK raw_source`
 * line. Neither was evidence: "Send camera" is the session role, and `raw_source` is logged when the encoder
 * starts, which happens only after the first receiver connects. In those runs no receiver ever connected
 * (GATT registration pool failure on the ASUS, status 257), so the encoder never started. The raw input was
 * accepted and the camera stayed inactive in all eight. This guard makes both facts explicit: the camera may
 * never start while a raw fixture is active, and the launch itself logs `BENCHMARK raw_input` so a missing
 * `raw_source` line can be told apart from a bypass.
 *
 * Raw input together with temporal layers is supported: the raw feed fills a flexible-YUV MediaCodec input
 * buffer (or a native libaom frame) and the layering key (`ts-schema`) or flags are independent of where the
 * pixels come from. There is therefore no refusal for the combination; an encoder that rejects layering at
 * configure still encodes one layer, and that is logged separately by the encoder.
 */
internal object RawInputGuard {
    /** The live camera, its preview and any camera restart may only run when no raw fixture is active. */
    fun cameraMayStart(rawInputActive: Boolean): Boolean = !rawInputActive

    /** One line at launch: the raw input was accepted, so the camera stays off for the whole session. */
    fun launchLine(
        fileName: String,
        temporalLayers: Boolean,
        hardwareTemporalLayers: Boolean,
        relayChildThinning: Boolean,
    ): String =
        "BENCHMARK raw_input: status=ACCEPTED | camera=inactive | file=$fileName | " +
            "temporalLayers=$temporalLayers | hardwareTemporalLayers=$hardwareTemporalLayers | " +
            "relayChildThinning=$relayChildThinning | raw_source=logged_when_the_encoder_starts_on_the_first_receiver"
}
