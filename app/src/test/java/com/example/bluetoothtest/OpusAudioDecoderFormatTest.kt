package com.example.bluetoothtest

import android.media.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusAudioDecoderFormatTest {
    @Test fun missingEncodingUsesTheDocumentedPcm16Default() {
        assertTrue(isTraceablePcmFormat(48_000, 1, null))
        assertTrue(isTraceablePcmFormat(48_000, 1, AudioFormat.ENCODING_PCM_16BIT))
    }

    @Test fun explicitOtherEncodingCannotBeRelabelledAsPcm16() {
        for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_DEFAULT, 999)) {
            assertFalse(isTraceablePcmFormat(48_000, 1, encoding))
        }
    }

    @Test fun defaultEncodingDoesNotSubstituteTheActualRateOrChannelCount() {
        for (encoding in listOf(null, AudioFormat.ENCODING_PCM_16BIT)) {
            assertFalse(isTraceablePcmFormat(44_100, 1, encoding))
            assertFalse(isTraceablePcmFormat(48_000, 2, encoding))
        }
    }
}
