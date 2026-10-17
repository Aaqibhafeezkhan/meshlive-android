// Experimental native libopus call codec (DEBUG `test_call_native_opus`, default off).
//
// Android's c2.android.opus encoder hard-codes OPUS_APPLICATION_AUDIO, never enables in-band FEC
// and its decoder never conceals a lost packet (AOSP frameworks/av C2SoftOpusEnc.cpp / C2SoftOpusDec.cpp).
// This bridge drives the pinned libopus directly with the WebRTC-style VOIP configuration. The bitstream
// is standard Opus (RFC 6716), so MediaCodec and iOS libopus peers decode it unchanged.
//
// One handle is used by one Kotlin thread at a time; the Kotlin wrappers serialize close().

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <new>

#include "opus.h"

namespace {

constexpr int kMaxFrameSamples = 5760;  // 120 ms at 48 kHz, the largest Opus frame.
constexpr int kMaxChannels = 2;
constexpr int kMaxPacketBytes = 4000;   // libopus recommended maximum packet size.

struct EncoderHandle {
    OpusEncoder* encoder = nullptr;
    int channels = 1;
};

struct DecoderHandle {
    OpusDecoder* decoder = nullptr;
    int channels = 1;
    int sampleRate = 48000;
};

void throwState(JNIEnv* env, const char* message, int code) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type == nullptr) return;
    char text[160];
    snprintf(text, sizeof(text), "%s (%d: %s)", message, code, opus_strerror(code));
    env->ThrowNew(type, text);
}

EncoderHandle* encoderFrom(jlong handle) { return reinterpret_cast<EncoderHandle*>(handle); }
DecoderHandle* decoderFrom(jlong handle) { return reinterpret_cast<DecoderHandle*>(handle); }

// Shared by both create entry points. [application] is OPUS_APPLICATION_VOIP (calls) or
// OPUS_APPLICATION_AUDIO; [speechSignal] adds OPUS_SIGNAL_VOICE (audio-only live Voice presets).
jlong createEncoder(
    JNIEnv* env, jint sampleRate, jint channels, jint bitRate, jint complexity,
    jint fecLossPercent, jboolean dtx, jboolean constrainedVbr, int application, bool speechSignal) {
    if (channels < 1 || channels > kMaxChannels) {
        throwState(env, "Unsupported Opus channel count", OPUS_BAD_ARG);
        return 0;
    }
    int error = OPUS_OK;
    OpusEncoder* encoder = opus_encoder_create(sampleRate, channels, application, &error);
    if (encoder == nullptr || error != OPUS_OK) {
        throwState(env, "opus_encoder_create failed", error);
        return 0;
    }
    const bool fec = fecLossPercent >= 0;
    int results[8] = {
        opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitRate)),
        opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity)),
        // Same rate-control family as c2.android.opus (constrained VBR) so the offered load matches.
        opus_encoder_ctl(encoder, OPUS_SET_VBR(1)),
        opus_encoder_ctl(encoder, OPUS_SET_VBR_CONSTRAINT(constrainedVbr ? 1 : 0)),
        opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(fec ? 1 : 0)),
        opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(fec ? fecLossPercent : 0)),
        opus_encoder_ctl(encoder, OPUS_SET_DTX(dtx ? 1 : 0)),
        speechSignal ? opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) : OPUS_OK,
    };
    for (int result : results) {
        if (result != OPUS_OK) {
            opus_encoder_destroy(encoder);
            throwState(env, "opus_encoder_ctl failed", result);
            return 0;
        }
    }
    auto* handle = new (std::nothrow) EncoderHandle();
    if (handle == nullptr) {
        opus_encoder_destroy(encoder);
        throwState(env, "Out of memory", OPUS_ALLOC_FAIL);
        return 0;
    }
    handle->encoder = encoder;
    handle->channels = channels;
    return reinterpret_cast<jlong>(handle);
}

}  // namespace

// Prototypes keep -Wmissing-prototypes (an error in release builds) quiet, as in native_aom_encoder.cpp.
extern "C" {
JNIEXPORT jstring JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeOpusVersion(JNIEnv* env, jobject);
JNIEXPORT jlong JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderCreate(JNIEnv* env, jobject, jint sampleRate, jint channels, jint bitRate, jint complexity, jint fecLossPercent, jboolean dtx, jboolean constrainedVbr);
JNIEXPORT jlong JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderCreateEx(JNIEnv* env, jobject, jint sampleRate, jint channels, jint bitRate, jint complexity, jint fecLossPercent, jboolean dtx, jboolean constrainedVbr, jint application, jboolean speechSignal);
JNIEXPORT jint JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderLookahead(JNIEnv* env, jobject, jlong handle);
JNIEXPORT jint JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderSetLossPercent(JNIEnv*, jobject, jlong handle, jint percent);
JNIEXPORT jint JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncode(JNIEnv* env, jobject, jlong handle, jbyteArray pcm, jint frameSamples, jbyteArray output);
JNIEXPORT void JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderDestroy(JNIEnv*, jobject, jlong handle);
JNIEXPORT jlong JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeDecoderCreate(JNIEnv* env, jobject, jint sampleRate, jint channels);
JNIEXPORT jint JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeDecode(JNIEnv* env, jobject, jlong handle, jbyteArray packet, jint packetLength, jint frameSamples, jboolean fec, jbyteArray output);
JNIEXPORT void JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativeDecoderDestroy(JNIEnv*, jobject, jlong handle);
JNIEXPORT jint JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativePacketSamples(JNIEnv* env, jobject, jbyteArray packet, jint packetLength, jint sampleRate);
JNIEXPORT jint JNICALL Java_com_example_bluetoothtest_NativeOpusBridge_nativePacketHasFec(JNIEnv* env, jobject, jbyteArray packet, jint packetLength);
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeOpusVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(opus_get_version_string());
}

/**
 * Creates a VOIP encoder. [fecLossPercent] < 0 leaves in-band FEC off; otherwise FEC is on and the
 * expected-loss hint is that percentage (libopus only spends LBRR bits for a non-zero hint).
 */
JNIEXPORT jlong JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderCreate(
    JNIEnv* env, jobject, jint sampleRate, jint channels, jint bitRate, jint complexity,
    jint fecLossPercent, jboolean dtx, jboolean constrainedVbr) {
    return createEncoder(env, sampleRate, channels, bitRate, complexity, fecLossPercent, dtx,
                         constrainedVbr, OPUS_APPLICATION_VOIP, false);
}

/**
 * Audio-only live entry point (Design AL): the same encoder with a caller-chosen application
 * (2048 = OPUS_APPLICATION_VOIP, 2049 = OPUS_APPLICATION_AUDIO) and an optional speech signal hint.
 * The call path never uses it.
 */
JNIEXPORT jlong JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderCreateEx(
    JNIEnv* env, jobject, jint sampleRate, jint channels, jint bitRate, jint complexity,
    jint fecLossPercent, jboolean dtx, jboolean constrainedVbr, jint application, jboolean speechSignal) {
    if (application != OPUS_APPLICATION_VOIP && application != OPUS_APPLICATION_AUDIO) {
        throwState(env, "Unsupported Opus application", OPUS_BAD_ARG);
        return 0;
    }
    return createEncoder(env, sampleRate, channels, bitRate, complexity, fecLossPercent, dtx,
                         constrainedVbr, application, speechSignal == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderLookahead(JNIEnv* env, jobject, jlong handle) {
    opus_int32 lookahead = 0;
    int result = opus_encoder_ctl(encoderFrom(handle)->encoder, OPUS_GET_LOOKAHEAD(&lookahead));
    if (result != OPUS_OK) throwState(env, "OPUS_GET_LOOKAHEAD failed", result);
    return lookahead;
}

JNIEXPORT jint JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderSetLossPercent(
    JNIEnv*, jobject, jlong handle, jint percent) {
    return opus_encoder_ctl(encoderFrom(handle)->encoder, OPUS_SET_PACKET_LOSS_PERC(percent));
}

/** Encodes one frame of little-endian PCM16; returns the packet length written to [output]. */
JNIEXPORT jint JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncode(
    JNIEnv* env, jobject, jlong handle, jbyteArray pcm, jint frameSamples, jbyteArray output) {
    EncoderHandle* encoder = encoderFrom(handle);
    const jsize pcmBytes = env->GetArrayLength(pcm);
    if (frameSamples <= 0 || frameSamples > kMaxFrameSamples ||
        pcmBytes < frameSamples * encoder->channels * 2) {
        throwState(env, "Opus encoder input does not hold one frame", OPUS_BAD_ARG);
        return 0;
    }
    opus_int16 samples[kMaxFrameSamples * kMaxChannels];
    env->GetByteArrayRegion(pcm, 0, frameSamples * encoder->channels * 2, reinterpret_cast<jbyte*>(samples));
    unsigned char packet[kMaxPacketBytes];
    const jsize capacity = env->GetArrayLength(output);
    const int length = opus_encode(encoder->encoder, samples, frameSamples, packet,
                                   capacity < kMaxPacketBytes ? capacity : kMaxPacketBytes);
    if (length < 0) {
        throwState(env, "opus_encode failed", length);
        return 0;
    }
    env->SetByteArrayRegion(output, 0, length, reinterpret_cast<const jbyte*>(packet));
    return length;
}

JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeEncoderDestroy(JNIEnv*, jobject, jlong handle) {
    EncoderHandle* encoder = encoderFrom(handle);
    if (encoder == nullptr) return;
    opus_encoder_destroy(encoder->encoder);
    delete encoder;
}

JNIEXPORT jlong JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeDecoderCreate(
    JNIEnv* env, jobject, jint sampleRate, jint channels) {
    if (channels < 1 || channels > kMaxChannels) {
        throwState(env, "Unsupported Opus channel count", OPUS_BAD_ARG);
        return 0;
    }
    int error = OPUS_OK;
    OpusDecoder* decoder = opus_decoder_create(sampleRate, channels, &error);
    if (decoder == nullptr || error != OPUS_OK) {
        throwState(env, "opus_decoder_create failed", error);
        return 0;
    }
    auto* handle = new (std::nothrow) DecoderHandle();
    if (handle == nullptr) {
        opus_decoder_destroy(decoder);
        throwState(env, "Out of memory", OPUS_ALLOC_FAIL);
        return 0;
    }
    handle->decoder = decoder;
    handle->channels = channels;
    handle->sampleRate = sampleRate;
    return reinterpret_cast<jlong>(handle);
}

/**
 * Decodes into little-endian PCM16 [output]; returns samples per channel.
 * - packet != null, fec == false: normal decode (up to 120 ms).
 * - packet == null: packet-loss concealment of [frameSamples] (libopus PLC).
 * - packet != null, fec == true: recover the frame lost *before* [packet] from its in-band FEC
 *   (LBRR) data; libopus falls back to PLC when the packet carries none. [frameSamples] must be
 *   the lost duration (a multiple of 2.5 ms).
 */
JNIEXPORT jint JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeDecode(
    JNIEnv* env, jobject, jlong handle, jbyteArray packet, jint packetLength, jint frameSamples,
    jboolean fec, jbyteArray output) {
    DecoderHandle* decoder = decoderFrom(handle);
    if (frameSamples <= 0 || frameSamples > kMaxFrameSamples) {
        throwState(env, "Opus decode frame size out of range", OPUS_BAD_ARG);
        return 0;
    }
    unsigned char data[kMaxPacketBytes];
    const unsigned char* dataPointer = nullptr;
    int length = 0;
    if (packet != nullptr) {
        if (packetLength <= 0 || packetLength > kMaxPacketBytes || packetLength > env->GetArrayLength(packet)) {
            throwState(env, "Opus packet length out of range", OPUS_BAD_ARG);
            return 0;
        }
        env->GetByteArrayRegion(packet, 0, packetLength, reinterpret_cast<jbyte*>(data));
        dataPointer = data;
        length = packetLength;
    }
    opus_int16 samples[kMaxFrameSamples * kMaxChannels];
    const int decoded = opus_decode(decoder->decoder, dataPointer, length, samples, frameSamples, fec ? 1 : 0);
    if (decoded < 0) {
        throwState(env, "opus_decode failed", decoded);
        return 0;
    }
    const jsize bytes = decoded * decoder->channels * 2;
    if (env->GetArrayLength(output) < bytes) {
        throwState(env, "Opus decode output is too small", OPUS_BUFFER_TOO_SMALL);
        return 0;
    }
    env->SetByteArrayRegion(output, 0, bytes, reinterpret_cast<const jbyte*>(samples));
    return decoded;
}

JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativeDecoderDestroy(JNIEnv*, jobject, jlong handle) {
    DecoderHandle* decoder = decoderFrom(handle);
    if (decoder == nullptr) return;
    opus_decoder_destroy(decoder->decoder);
    delete decoder;
}

/** Samples per channel the packet decodes to at 48 kHz, or a negative libopus error. */
JNIEXPORT jint JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativePacketSamples(
    JNIEnv* env, jobject, jbyteArray packet, jint packetLength, jint sampleRate) {
    if (packetLength <= 0 || packetLength > kMaxPacketBytes || packetLength > env->GetArrayLength(packet)) {
        return OPUS_BAD_ARG;
    }
    unsigned char data[kMaxPacketBytes];
    env->GetByteArrayRegion(packet, 0, packetLength, reinterpret_cast<jbyte*>(data));
    return opus_packet_get_nb_samples(data, packetLength, sampleRate);
}

/** 1 when the packet carries in-band FEC (LBRR) for the previous frame, 0 when not, < 0 on error. */
JNIEXPORT jint JNICALL
Java_com_example_bluetoothtest_NativeOpusBridge_nativePacketHasFec(
    JNIEnv* env, jobject, jbyteArray packet, jint packetLength) {
    if (packetLength <= 0 || packetLength > kMaxPacketBytes || packetLength > env->GetArrayLength(packet)) {
        return OPUS_BAD_ARG;
    }
    unsigned char data[kMaxPacketBytes];
    env->GetByteArrayRegion(packet, 0, packetLength, reinterpret_cast<jbyte*>(data));
    return opus_packet_has_lbrr(data, packetLength);
}

}  // extern "C"
