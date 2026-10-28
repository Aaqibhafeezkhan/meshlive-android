// Bundled libvpx VP9 encoder (DECISIONS "Design VX"). Written from libvpx's public API headers
// (vpx/vpx_encoder.h, vpx/vp8cx.h); mirrors native_aom_encoder.cpp so the Kotlin side can treat both
// native encoders alike. Output is a standard VP9 profile 0 bitstream (one frame per packet, no
// superframes: one spatial and one temporal layer, no lag), sent as codec VP9.
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "vpx/vp8cx.h"
#include "vpx/vpx_encoder.h"

extern "C" {
JNIEXPORT jstring JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeVersion(JNIEnv*, jclass);
JNIEXPORT jlong JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeCreate(
    JNIEnv*, jclass, jint, jint, jint, jint, jint, jint, jboolean, jboolean,
    jboolean, jint, jint, jint);
JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeEncode(
    JNIEnv*, jclass, jlong, jobject, jlong, jboolean);
JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeFlush(JNIEnv*, jclass,
                                                           jlong);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetBitrate(JNIEnv*, jclass,
                                                                jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetCpuUsed(JNIEnv*, jclass,
                                                                jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetMaxInterBitratePct(
    JNIEnv*, jclass, jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetKeyFrameQuantizerReference(
    JNIEnv*, jclass, jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeClose(JNIEnv*, jclass,
                                                           jlong);
}

namespace {

// Same rule as the libaom path: a forced keyframe may use at most this many quantizer steps
// (0-63 scale) below the last inter frame, so a join/recovery keyframe stays a few frames large.
constexpr int kKeyFrameQuantizerHeadroom = 8;
constexpr int kMinQuantizer = 4;

struct VpxEncoder {
  vpx_codec_ctx_t codec{};
  vpx_codec_enc_cfg_t config{};
  int width = 0;
  int height = 0;
  int fps = 0;
  bool initialized = false;
  int last_inter_quantizer = -1;

  ~VpxEncoder() {
    if (initialized) vpx_codec_destroy(&codec);
  }
};

struct OutputPacket {
  std::vector<uint8_t> bytes;
  int64_t pts;
  bool key_frame;
};

void ThrowIllegalState(JNIEnv* env, const std::string& message) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type != nullptr) env->ThrowNew(type, message.c_str());
}

bool Check(JNIEnv* env, VpxEncoder* encoder, vpx_codec_err_t result,
           const char* operation) {
  if (result == VPX_CODEC_OK) return true;
  std::string message(operation);
  message += ": ";
  message += vpx_codec_err_to_string(result);
  if (encoder != nullptr && encoder->initialized) {
    const char* detail = vpx_codec_error_detail(&encoder->codec);
    if (detail != nullptr) {
      message += " (";
      message += detail;
      message += ")";
    }
  }
  ThrowIllegalState(env, message);
  return false;
}

VpxEncoder* FromHandle(jlong handle) {
  return reinterpret_cast<VpxEncoder*>(static_cast<intptr_t>(handle));
}

// vpx_codec_control is a type-checked macro per control id, so each control is spelled out.
#define BTV_VPX_CONTROL(env, encoder, id, value) \
  Check(env, encoder, vpx_codec_control(&(encoder)->codec, id, value), #id)

// VP9 tile columns are given as log2; libvpx caps them by the frame width (at least 256 pixels per
// tile column). Up to four tile columns, with row-based multithreading inside each tile.
int TileColumnsLog2(int threads) {
  if (threads >= 4) return 2;
  if (threads >= 2) return 1;
  return 0;
}

jobjectArray ToJava(JNIEnv* env, const std::vector<OutputPacket>& outputs,
                    int quantizer) {
  jclass packet_class =
      env->FindClass("com/example/bluetoothtest/NativeAomPacket");
  if (packet_class == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(
      static_cast<jsize>(outputs.size()), packet_class, nullptr);
  if (result == nullptr || outputs.empty()) return result;
  jmethodID constructor = env->GetMethodID(packet_class, "<init>", "([BJZI)V");
  if (constructor == nullptr) return nullptr;
  for (size_t index = 0; index < outputs.size(); ++index) {
    const OutputPacket& output = outputs[index];
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(output.bytes.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.bytes.size()),
                            reinterpret_cast<const jbyte*>(output.bytes.data()));
    jobject item = env->NewObject(packet_class, constructor, bytes,
                                  static_cast<jlong>(output.pts),
                                  static_cast<jboolean>(output.key_frame),
                                  static_cast<jint>(quantizer));
    env->SetObjectArrayElement(result, static_cast<jsize>(index), item);
    env->DeleteLocalRef(item);
    env->DeleteLocalRef(bytes);
  }
  return result;
}

jobjectArray CollectPackets(JNIEnv* env, VpxEncoder* encoder) {
  std::vector<OutputPacket> outputs;
  vpx_codec_iter_t iterator = nullptr;
  const vpx_codec_cx_pkt_t* packet = nullptr;
  while ((packet = vpx_codec_get_cx_data(&encoder->codec, &iterator)) !=
         nullptr) {
    if (packet->kind != VPX_CODEC_CX_FRAME_PKT) continue;
    const auto* data = static_cast<const uint8_t*>(packet->data.frame.buf);
    outputs.push_back(OutputPacket{
        std::vector<uint8_t>(data, data + packet->data.frame.sz),
        static_cast<int64_t>(packet->data.frame.pts),
        (packet->data.frame.flags & VPX_FRAME_IS_KEY) != 0,
    });
  }
  int quantizer = -1;
  if (!outputs.empty()) {
    if (!Check(env, encoder,
               vpx_codec_control(&encoder->codec, VP8E_GET_LAST_QUANTIZER_64,
                                 &quantizer),
               "VP8E_GET_LAST_QUANTIZER_64")) {
      return nullptr;
    }
    if (!outputs.back().key_frame) encoder->last_inter_quantizer = quantizer;
  }
  return ToJava(env, outputs, quantizer);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeVersion(JNIEnv* env,
                                                             jclass) {
  return env->NewStringUTF(vpx_codec_version_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeCreate(
    JNIEnv* env, jclass, jint width, jint height, jint fps, jint bitrate_bps,
    jint cpu_used, jint threads, jboolean variable_bitrate,
    jboolean drop_frames, jboolean error_resilient, jint aq_mode,
    jint max_quantizer, jint rate_buffer_ms) {
  if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0 ||
      fps <= 0 || bitrate_bps <= 0 || cpu_used < 5 || cpu_used > 9 ||
      threads <= 0 || threads > 16 || aq_mode < 0 || aq_mode > 3 ||
      max_quantizer < 20 || max_quantizer > 63 || rate_buffer_ms < 100 ||
      rate_buffer_ms > 6000) {
    ThrowIllegalState(env, "Invalid native libvpx configuration");
    return 0;
  }

  auto encoder = std::make_unique<VpxEncoder>();
  encoder->width = width;
  encoder->height = height;
  encoder->fps = fps;
  if (!Check(env, encoder.get(),
             vpx_codec_enc_config_default(vpx_codec_vp9_cx(), &encoder->config,
                                          0),
             "vpx_codec_enc_config_default")) {
    return 0;
  }

  vpx_codec_enc_cfg_t& config = encoder->config;
  config.g_w = static_cast<unsigned int>(width);
  config.g_h = static_cast<unsigned int>(height);
  config.g_threads = static_cast<unsigned int>(threads);
  config.g_timebase.num = 1;
  config.g_timebase.den = 1000000;
  config.g_profile = 0;
  config.g_input_bit_depth = 8;
  config.g_bit_depth = VPX_BITS_8;
  config.g_pass = VPX_RC_ONE_PASS;
  config.g_lag_in_frames = 0;
  config.g_error_resilient =
      error_resilient ? VPX_ERROR_RESILIENT_DEFAULT : 0;
  // The app decides every keyframe (interval and on request), as on the libaom path.
  config.kf_mode = VPX_KF_DISABLED;
  config.rc_end_usage = variable_bitrate ? VPX_VBR : VPX_CBR;
  config.rc_target_bitrate =
      static_cast<unsigned int>(std::max(1, bitrate_bps / 1000));
  config.rc_min_quantizer = kMinQuantizer;
  config.rc_max_quantizer = static_cast<unsigned int>(max_quantizer);
  config.rc_undershoot_pct = 50;
  config.rc_overshoot_pct = 50;
  config.rc_buf_initial_sz =
      static_cast<unsigned int>(std::min(rate_buffer_ms, 500));
  config.rc_buf_optimal_sz =
      static_cast<unsigned int>(std::min(rate_buffer_ms, 600));
  config.rc_buf_sz = static_cast<unsigned int>(rate_buffer_ms);
  config.rc_dropframe_thresh = drop_frames ? 30 : 0;

  if (!Check(env, encoder.get(),
             vpx_codec_enc_init(&encoder->codec, vpx_codec_vp9_cx(), &config,
                                0),
             "vpx_codec_enc_init")) {
    return 0;
  }
  encoder->initialized = true;

  VpxEncoder* e = encoder.get();
  if (!BTV_VPX_CONTROL(env, e, VP8E_SET_CPUUSED, static_cast<int>(cpu_used)) ||
      !BTV_VPX_CONTROL(env, e, VP9E_SET_AQ_MODE,
                       static_cast<unsigned int>(aq_mode)) ||
      !BTV_VPX_CONTROL(env, e, VP8E_SET_MAX_INTRA_BITRATE_PCT, 300u) ||
      !BTV_VPX_CONTROL(env, e, VP8E_SET_STATIC_THRESHOLD, 1u) ||
      !BTV_VPX_CONTROL(env, e, VP9E_SET_ROW_MT, 1u) ||
      !BTV_VPX_CONTROL(env, e, VP9E_SET_TILE_COLUMNS,
                       TileColumnsLog2(threads)) ||
      !BTV_VPX_CONTROL(env, e, VP9E_SET_FRAME_PARALLEL_DECODING, 0u) ||
      !BTV_VPX_CONTROL(env, e, VP9E_SET_TUNE_CONTENT,
                       static_cast<int>(VP9E_CONTENT_DEFAULT))) {
    return 0;
  }
  if (drop_frames && !variable_bitrate &&
      !BTV_VPX_CONTROL(env, e, VP9E_SET_POSTENCODE_DROP, 1u)) {
    return 0;
  }
  return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder.release()));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeEncode(
    JNIEnv* env, jclass, jlong handle, jobject i420_buffer,
    jlong presentation_time_us, jboolean force_key_frame) {
  VpxEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized) {
    ThrowIllegalState(env, "Native libvpx encoder is closed");
    return nullptr;
  }
  auto* pixels =
      static_cast<uint8_t*>(env->GetDirectBufferAddress(i420_buffer));
  const jlong capacity = env->GetDirectBufferCapacity(i420_buffer);
  const size_t required =
      static_cast<size_t>(encoder->width) * encoder->height * 3 / 2;
  if (pixels == nullptr || capacity < static_cast<jlong>(required)) {
    ThrowIllegalState(env, "Native libvpx input must be a direct I420 buffer");
    return nullptr;
  }
  vpx_image_t image{};
  if (vpx_img_wrap(&image, VPX_IMG_FMT_I420,
                   static_cast<unsigned int>(encoder->width),
                   static_cast<unsigned int>(encoder->height), 1,
                   pixels) == nullptr) {
    ThrowIllegalState(env, "Could not wrap native I420 input");
    return nullptr;
  }
  const unsigned long duration =
      static_cast<unsigned long>(1000000 / encoder->fps);
  const vpx_enc_frame_flags_t flags = force_key_frame ? VPX_EFLAG_FORCE_KF : 0;

  const unsigned int base_min_quantizer = encoder->config.rc_min_quantizer;
  bool floored = false;
  if (force_key_frame && encoder->last_inter_quantizer >= 0) {
    const int floor_q =
        std::min(static_cast<int>(encoder->config.rc_max_quantizer),
                 encoder->last_inter_quantizer - kKeyFrameQuantizerHeadroom);
    if (floor_q > static_cast<int>(base_min_quantizer)) {
      encoder->config.rc_min_quantizer = static_cast<unsigned int>(floor_q);
      floored = true;
      if (!Check(env, encoder,
                 vpx_codec_enc_config_set(&encoder->codec, &encoder->config),
                 "vpx_codec_enc_config_set keyframe floor")) {
        encoder->config.rc_min_quantizer = base_min_quantizer;
        return nullptr;
      }
    }
  }
  const vpx_codec_err_t encoded =
      vpx_codec_encode(&encoder->codec, &image, presentation_time_us, duration,
                       flags, VPX_DL_REALTIME);
  if (floored) {
    encoder->config.rc_min_quantizer = base_min_quantizer;
    if (!Check(env, encoder,
               vpx_codec_enc_config_set(&encoder->codec, &encoder->config),
               "vpx_codec_enc_config_set restore")) {
      return nullptr;
    }
  }
  if (!Check(env, encoder, encoded, "vpx_codec_encode")) return nullptr;
  return CollectPackets(env, encoder);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeFlush(JNIEnv* env, jclass,
                                                           jlong handle) {
  VpxEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized) {
    ThrowIllegalState(env, "Native libvpx encoder is closed");
    return nullptr;
  }
  if (!Check(env, encoder,
             vpx_codec_encode(&encoder->codec, nullptr, 0, 0, 0,
                              VPX_DL_REALTIME),
             "vpx_codec_encode flush")) {
    return nullptr;
  }
  return CollectPackets(env, encoder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetBitrate(
    JNIEnv* env, jclass, jlong handle, jint bitrate_bps) {
  VpxEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized || bitrate_bps <= 0) {
    ThrowIllegalState(env, "Invalid native libvpx bitrate update");
    return;
  }
  encoder->config.rc_target_bitrate =
      static_cast<unsigned int>(std::max(1, bitrate_bps / 1000));
  Check(env, encoder,
        vpx_codec_enc_config_set(&encoder->codec, &encoder->config),
        "vpx_codec_enc_config_set");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetCpuUsed(
    JNIEnv* env, jclass, jlong handle, jint cpu_used) {
  VpxEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized || cpu_used < 5 ||
      cpu_used > 9) {
    ThrowIllegalState(env, "Invalid native libvpx speed update");
    return;
  }
  BTV_VPX_CONTROL(env, encoder, VP8E_SET_CPUUSED, static_cast<int>(cpu_used));
}

// Caps one inter frame at a percentage of the average frame (0 = libvpx default, unlimited).
extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetMaxInterBitratePct(
    JNIEnv* env, jclass, jlong handle, jint percent) {
  VpxEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized || percent < 0) return;
  BTV_VPX_CONTROL(env, encoder, VP9E_SET_MAX_INTER_BITRATE_PCT,
                  static_cast<unsigned int>(percent));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeSetKeyFrameQuantizerReference(
    JNIEnv*, jclass, jlong handle, jint quantizer) {
  VpxEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || quantizer < 0 || quantizer > 63) return;
  encoder->last_inter_quantizer = quantizer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeVpxBridge_nativeClose(JNIEnv*, jclass,
                                                           jlong handle) {
  delete FromHandle(handle);
}
