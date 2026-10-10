package com.example.bluetoothtest

import android.content.Context
import android.media.MediaFormat
import java.io.Closeable

class DebugMediaTrace private constructor() : Closeable {
    fun record(
        presentationTimeUs: Long,
        flags: Int,
        payload: ByteArray,
        qp: Int?,
        pictureType: Int?,
        encodeLatencyMs: Double?,
        requestedBitrate: Int?,
        integrity: String,
    ) = Unit

    fun setRawTimeline(file: String, fixtureFrames: Long, originPtsUs: Long, endPtsExclusiveUs: Long) = Unit
    fun setCodecFormat(format: MediaFormat) = Unit

    override fun close() = Unit

    companion object {
        fun startIfRequested(
            context: Context,
            requestedSessionName: String?,
            role: String,
            codecId: Int,
            width: Int,
            height: Int,
            fps: Int,
            encoderName: String? = null,
        ): DebugMediaTrace? = null
    }
}
