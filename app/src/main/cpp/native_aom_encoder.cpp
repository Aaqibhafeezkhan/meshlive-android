#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "aom/aom_encoder.h"
#include "aom/aomcx.h"

extern "C" {
JNIEXPORT jstring JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeVersion(JNIEnv*, jclass);
JNIEXPORT jboolean JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeCopyPlane(
    JNIEnv*, jclass, jobject, jint, jint, jint, jint, jobject, jint, jint,
    jint, jint, jint, jint);
JNIEXPORT jboolean JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeCopyYuv420ToI420(
    JNIEnv*, jclass, jobject, jint, jint, jint, jint, jobject, jint, jint,
    jint, jint, jobject, jint, jint, jint, jint, jint, jint, jobject, jint,
    jint);
JNIEXPORT jlong JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeCreate(
    JNIEnv*, jclass, jint, jint, jint, jint, jint, jint, jboolean, jint,
    jboolean, jboolean, jint, jboolean, jint, jint, jint, jboolean, jint,
    jboolean);
JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeEncode(
    JNIEnv*, jclass, jlong, jobject, jlong, jboolean, jint);
JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeFlush(
    JNIEnv*, jclass, jlong);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetBitrate(
    JNIEnv*, jclass, jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetCpuUsed(
    JNIEnv*, jclass, jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetMaxInterBitratePct(
    JNIEnv*, jclass, jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetKeyFrameQuantizerReference(
    JNIEnv*, jclass, jlong, jint);
JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeClose(
    JNIEnv*, jclass, jlong);
}

namespace {

// Forced keyframes may use at most this many quantizer steps (0-63 scale) below the last inter
// frame; about 1.3 halvings of size, so a join/recovery keyframe stays a few frames large.
constexpr int kKeyFrameQuantizerHeadroom = 8;

// Experimental temporal layering (off by default; see TemporalLayerThinning.kt): explicit
// base-layer structure mirroring libaom's own one-layer real-time structure.
constexpr int kGoldenRefreshBaseFrames = 32;
constexpr int kRotatingSlots = 6;
constexpr int kGoldenSlot = 6;
constexpr int kAltRefLagBaseFrames = 2;

struct NativeEncoder {
  aom_codec_ctx_t codec{};
  aom_codec_enc_cfg_t config{};
  int width = 0;
  int height = 0;
  int fps = 0;
  bool initialized = false;
  // Last inter-frame quantizer (0-63), used to floor forced keyframes.
  int last_inter_quantizer = -1;
  // Frames encoded so far (libaom's frame_number for its own real-time reference structure).
  int64_t frames_encoded = 0;
  // Next base frame index of the experimental explicit structure; -1 until layering starts.
  int64_t explicit_base = -1;

  ~NativeEncoder() {
    if (initialized) aom_codec_destroy(&codec);
  }
};

struct OutputPacket {
  const uint8_t* data;
  size_t size;
  int64_t pts;
  bool key_frame;
};

void ThrowIllegalState(JNIEnv* env, const std::string& message) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type != nullptr) env->ThrowNew(type, message.c_str());
}

bool Check(JNIEnv* env,
           NativeEncoder* encoder,
           aom_codec_err_t result,
           const char* operation) {
  if (result == AOM_CODEC_OK) return true;
  std::string message(operation);
  message += ": ";
  message += aom_codec_err_to_string(result);
  if (encoder != nullptr) {
    const char* detail = aom_codec_error_detail(&encoder->codec);
    if (detail != nullptr) {
      message += " (";
      message += detail;
      message += ")";
    }
  }
  ThrowIllegalState(env, message);
  return false;
}

NativeEncoder* FromHandle(jlong handle) {
  return reinterpret_cast<NativeEncoder*>(static_cast<intptr_t>(handle));
}

bool SetControl(JNIEnv* env,
                NativeEncoder* encoder,
                int control,
                int value,
                const char* name) {
  return Check(env, encoder,
               aom_codec_control(&encoder->codec, control, value), name);
}

bool SetRealtimeToolProfile(JNIEnv* env, NativeEncoder* encoder) {
  // Keep this in sync with WebRTC's libaom real-time camera configuration.
  return SetControl(env, encoder, AV1E_SET_ENABLE_TPL_MODEL, 0,
                    "AV1E_SET_ENABLE_TPL_MODEL") &&
         SetControl(env, encoder, AV1E_SET_DELTAQ_MODE, 0,
                    "AV1E_SET_DELTAQ_MODE") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_ORDER_HINT, 0,
                    "AV1E_SET_ENABLE_ORDER_HINT") &&
         SetControl(env, encoder, AV1E_SET_COEFF_COST_UPD_FREQ, 3,
                    "AV1E_SET_COEFF_COST_UPD_FREQ") &&
         SetControl(env, encoder, AV1E_SET_MODE_COST_UPD_FREQ, 3,
                    "AV1E_SET_MODE_COST_UPD_FREQ") &&
         SetControl(env, encoder, AV1E_SET_MV_COST_UPD_FREQ, 3,
                    "AV1E_SET_MV_COST_UPD_FREQ") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_PALETTE, 0,
                    "AV1E_SET_ENABLE_PALETTE") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_OBMC, 0,
                    "AV1E_SET_ENABLE_OBMC") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_WARPED_MOTION, 0,
                    "AV1E_SET_ENABLE_WARPED_MOTION") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_GLOBAL_MOTION, 0,
                    "AV1E_SET_ENABLE_GLOBAL_MOTION") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_REF_FRAME_MVS, 0,
                    "AV1E_SET_ENABLE_REF_FRAME_MVS") &&
         SetControl(env, encoder, AV1E_SET_SUPERBLOCK_SIZE,
                    AOM_SUPERBLOCK_SIZE_DYNAMIC,
                    "AV1E_SET_SUPERBLOCK_SIZE") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_CFL_INTRA, 0,
                    "AV1E_SET_ENABLE_CFL_INTRA") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_SMOOTH_INTRA, 0,
                    "AV1E_SET_ENABLE_SMOOTH_INTRA") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_ANGLE_DELTA, 0,
                    "AV1E_SET_ENABLE_ANGLE_DELTA") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_FILTER_INTRA, 0,
                    "AV1E_SET_ENABLE_FILTER_INTRA") &&
         SetControl(env, encoder, AV1E_SET_INTRA_DEFAULT_TX_ONLY, 1,
                    "AV1E_SET_INTRA_DEFAULT_TX_ONLY") &&
         SetControl(env, encoder, AV1E_SET_DISABLE_TRELLIS_QUANT, 1,
                    "AV1E_SET_DISABLE_TRELLIS_QUANT") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_DIST_WTD_COMP, 0,
                    "AV1E_SET_ENABLE_DIST_WTD_COMP") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_DIFF_WTD_COMP, 0,
                    "AV1E_SET_ENABLE_DIFF_WTD_COMP") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_DUAL_FILTER, 0,
                    "AV1E_SET_ENABLE_DUAL_FILTER") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_INTERINTRA_COMP, 0,
                    "AV1E_SET_ENABLE_INTERINTRA_COMP") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_INTERINTRA_WEDGE, 0,
                    "AV1E_SET_ENABLE_INTERINTRA_WEDGE") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_INTRA_EDGE_FILTER, 0,
                    "AV1E_SET_ENABLE_INTRA_EDGE_FILTER") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_INTRABC, 0,
                    "AV1E_SET_ENABLE_INTRABC") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_MASKED_COMP, 0,
                    "AV1E_SET_ENABLE_MASKED_COMP") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_PAETH_INTRA, 0,
                    "AV1E_SET_ENABLE_PAETH_INTRA") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_QM, 0,
                    "AV1E_SET_ENABLE_QM") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_RECT_PARTITIONS, 0,
                    "AV1E_SET_ENABLE_RECT_PARTITIONS") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_RESTORATION, 0,
                    "AV1E_SET_ENABLE_RESTORATION") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_SMOOTH_INTERINTRA, 0,
                    "AV1E_SET_ENABLE_SMOOTH_INTERINTRA") &&
         SetControl(env, encoder, AV1E_SET_ENABLE_TX64, 0,
                    "AV1E_SET_ENABLE_TX64");
}

jobjectArray EmptyPackets(JNIEnv* env) {
  jclass packet_class =
      env->FindClass("com/example/bluetoothtest/NativeAomPacket");
  if (packet_class == nullptr) return nullptr;
  return env->NewObjectArray(0, packet_class, nullptr);
}

jobjectArray CollectPackets(JNIEnv* env, NativeEncoder* encoder) {
  std::vector<OutputPacket> outputs;
  aom_codec_iter_t iterator = nullptr;
  const aom_codec_cx_pkt_t* packet = nullptr;
  while ((packet = aom_codec_get_cx_data(&encoder->codec, &iterator)) !=
         nullptr) {
    if (packet->kind != AOM_CODEC_CX_FRAME_PKT) continue;
    outputs.push_back(OutputPacket{
        static_cast<const uint8_t*>(packet->data.frame.buf),
        packet->data.frame.sz,
        packet->data.frame.pts,
        (packet->data.frame.flags & AOM_FRAME_IS_KEY) != 0,
    });
  }
  if (outputs.empty()) return EmptyPackets(env);

  int quantizer = -1;
  Check(env, encoder,
        aom_codec_control(&encoder->codec, AOME_GET_LAST_QUANTIZER_64,
                          &quantizer),
        "AOME_GET_LAST_QUANTIZER_64");
  if (env->ExceptionCheck()) return nullptr;
  if (!outputs.back().key_frame) encoder->last_inter_quantizer = quantizer;

  jclass packet_class =
      env->FindClass("com/example/bluetoothtest/NativeAomPacket");
  if (packet_class == nullptr) return nullptr;
  jmethodID constructor = env->GetMethodID(packet_class, "<init>", "([BJZI)V");
  if (constructor == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(
      static_cast<jsize>(outputs.size()), packet_class, nullptr);
  if (result == nullptr) return nullptr;

  for (size_t index = 0; index < outputs.size(); ++index) {
    const OutputPacket& output = outputs[index];
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(output.size));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size),
                            reinterpret_cast<const jbyte*>(output.data));
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

bool CopyPlane(const uint8_t* source,
               size_t source_size,
               int source_width,
               int source_height,
               int row_stride,
               int pixel_stride,
               uint8_t* target,
               int target_width,
               int target_height) {
  if (source == nullptr || target == nullptr || source_width <= 0 ||
      source_height <= 0 || row_stride <= 0 || pixel_stride <= 0 ||
      target_width <= 0 || target_height <= 0) {
    return false;
  }
  int crop_left = 0;
  int crop_top = 0;
  int crop_width = source_width;
  int crop_height = source_height;
  if (source_width * target_height > target_width * source_height) {
    crop_width = source_height * target_width / target_height;
    crop_left = (source_width - crop_width) / 2;
  } else {
    crop_height = source_width * target_height / target_width;
    crop_top = (source_height - crop_height) / 2;
  }

  for (int row = 0; row < target_height; ++row) {
    const int source_row = crop_top + row * crop_height / target_height;
    const size_t row_offset = static_cast<size_t>(source_row) * row_stride;
    uint8_t* target_row = target + static_cast<size_t>(row) * target_width;
    if (crop_width == target_width && pixel_stride == 1) {
      const size_t source_offset = row_offset + crop_left;
      if (source_offset + static_cast<size_t>(target_width) > source_size) {
        return false;
      }
      std::memcpy(target_row, source + source_offset,
                  static_cast<size_t>(target_width));
      continue;
    }
    for (int column = 0; column < target_width; ++column) {
      const int source_column =
          crop_left + column * crop_width / target_width;
      const size_t source_offset =
          row_offset + static_cast<size_t>(source_column) * pixel_stride;
      if (source_offset >= source_size) return false;
      target_row[column] = source[source_offset];
    }
  }
  return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeVersion(
    JNIEnv* env,
    jclass) {
  return env->NewStringUTF(aom_codec_version_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeCopyPlane(
    JNIEnv* env, jclass, jobject source_buffer, jint source_offset,
    jint source_limit, jint source_row_stride, jint source_pixel_stride,
    jobject target_buffer, jint target_offset, jint target_limit,
    jint target_row_stride, jint target_pixel_stride, jint width, jint height) {
  auto* source = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source_buffer));
  auto* target = static_cast<uint8_t*>(env->GetDirectBufferAddress(target_buffer));
  if (source == nullptr || target == nullptr || width <= 0 || height <= 0 ||
      source_offset < 0 || target_offset < 0 || source_limit < source_offset ||
      target_limit < target_offset || source_pixel_stride <= 0 || target_pixel_stride <= 0 ||
      source_row_stride <= 0 || target_row_stride <= 0 ||
      source_limit > env->GetDirectBufferCapacity(source_buffer) ||
      target_limit > env->GetDirectBufferCapacity(target_buffer)) return JNI_FALSE;
  const int64_t source_row_bytes = static_cast<int64_t>(width - 1) * source_pixel_stride + 1;
  const int64_t target_row_bytes = static_cast<int64_t>(width - 1) * target_pixel_stride + 1;
  if (source_row_bytes > source_row_stride || target_row_bytes > target_row_stride ||
      static_cast<int64_t>(height - 1) * source_row_stride + source_row_bytes > source_limit - source_offset ||
      static_cast<int64_t>(height - 1) * target_row_stride + target_row_bytes > target_limit - target_offset) return JNI_FALSE;
  for (int row = 0; row < height; ++row) {
    const auto* source_row = source + source_offset + static_cast<size_t>(row) * source_row_stride;
    auto* target_row = target + target_offset + static_cast<size_t>(row) * target_row_stride;
    if (source_pixel_stride == 1 && target_pixel_stride == 1) {
      std::memcpy(target_row, source_row, static_cast<size_t>(width));
    } else {
      for (int column = 0; column < width; ++column) {
        target_row[static_cast<size_t>(column) * target_pixel_stride] =
            source_row[static_cast<size_t>(column) * source_pixel_stride];
      }
    }
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeCopyYuv420ToI420(
    JNIEnv* env,
    jclass,
    jobject y_buffer,
    jint y_offset,
    jint y_limit,
    jint y_row_stride,
    jint y_pixel_stride,
    jobject u_buffer,
    jint u_offset,
    jint u_limit,
    jint u_row_stride,
    jint u_pixel_stride,
    jobject v_buffer,
    jint v_offset,
    jint v_limit,
    jint v_row_stride,
    jint v_pixel_stride,
    jint source_width,
    jint source_height,
    jobject target_buffer,
    jint target_width,
    jint target_height) {
  auto* y_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(y_buffer));
  auto* u_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(u_buffer));
  auto* v_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(v_buffer));
  auto* target =
      static_cast<uint8_t*>(env->GetDirectBufferAddress(target_buffer));
  const jlong target_capacity = env->GetDirectBufferCapacity(target_buffer);
  const size_t required =
      static_cast<size_t>(target_width) * target_height * 3 / 2;
  if (y_base == nullptr || u_base == nullptr || v_base == nullptr ||
      target == nullptr || y_offset < 0 || u_offset < 0 || v_offset < 0 ||
      y_limit < y_offset || u_limit < u_offset || v_limit < v_offset ||
      source_width <= 0 || source_height <= 0 || target_width <= 0 ||
      target_height <= 0 || (target_width & 1) != 0 ||
      (target_height & 1) != 0 || target_capacity < 0 ||
      static_cast<size_t>(target_capacity) < required) {
    return JNI_FALSE;
  }

  const bool copied_y = CopyPlane(
      y_base + y_offset, static_cast<size_t>(y_limit - y_offset), source_width,
      source_height, y_row_stride, y_pixel_stride, target, target_width,
      target_height);
  const size_t chroma_offset =
      static_cast<size_t>(target_width) * target_height;
  // 4:2:0 chroma is (n + 1) / 2 samples (libyuv Android420ToI420); the target is even-sized.
  const int chroma_width = (target_width + 1) / 2;
  const int chroma_height = (target_height + 1) / 2;
  const int source_chroma_width = (source_width + 1) / 2;
  const int source_chroma_height = (source_height + 1) / 2;
  const bool copied_u = CopyPlane(
      u_base + u_offset, static_cast<size_t>(u_limit - u_offset),
      source_chroma_width, source_chroma_height, u_row_stride, u_pixel_stride,
      target + chroma_offset, chroma_width, chroma_height);
  const bool copied_v = CopyPlane(
      v_base + v_offset, static_cast<size_t>(v_limit - v_offset),
      source_chroma_width, source_chroma_height, v_row_stride, v_pixel_stride,
      target + chroma_offset + static_cast<size_t>(chroma_width) * chroma_height,
      chroma_width, chroma_height);
  return copied_y && copied_u && copied_v ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeCreate(
    JNIEnv* env,
    jclass,
    jint width,
    jint height,
    jint fps,
    jint bitrate_bps,
    jint cpu_used,
    jint threads,
    jboolean quality_mode,
    jint lag_frames,
    jboolean variable_bitrate,
    jboolean drop_frames,
    jint aq_mode,
    jboolean tune_ssim,
    jint noise_sensitivity,
    jint max_quantizer,
    jint rate_buffer_ms,
    jboolean enable_cdef,
    jint max_reference_frames,
    jboolean realtime_tool_profile) {
  if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0 ||
      fps <= 0 || bitrate_bps <= 0 || cpu_used < 4 || cpu_used > 10 ||
      threads <= 0 || aq_mode < 0 || aq_mode > 3 || noise_sensitivity < 0 ||
      noise_sensitivity > 6 || max_quantizer < 10 || max_quantizer > 63 ||
      rate_buffer_ms < 100 || rate_buffer_ms > 6000 ||
      max_reference_frames < 3 || max_reference_frames > 7 ||
      lag_frames < 0 || lag_frames > 32 || (!quality_mode && lag_frames != 0) ||
      (quality_mode && cpu_used > 9)) {
    ThrowIllegalState(env, "Invalid native libaom configuration");
    return 0;
  }

  auto encoder = std::make_unique<NativeEncoder>();
  encoder->width = width;
  encoder->height = height;
  encoder->fps = fps;
  if (!Check(env, encoder.get(),
             aom_codec_enc_config_default(aom_codec_av1_cx(),
                                          &encoder->config,
                                          quality_mode ? AOM_USAGE_GOOD_QUALITY
                                                       : AOM_USAGE_REALTIME),
             "aom_codec_enc_config_default")) {
    return 0;
  }

  aom_codec_enc_cfg_t& config = encoder->config;
  config.g_w = static_cast<unsigned int>(width);
  config.g_h = static_cast<unsigned int>(height);
  config.g_threads = static_cast<unsigned int>(threads);
  config.g_timebase.num = 1;
  config.g_timebase.den = 1000000;
  config.g_input_bit_depth = 8;
  config.g_profile = 0;
  config.g_pass = AOM_RC_ONE_PASS;
  config.g_lag_in_frames = static_cast<unsigned int>(lag_frames);
  config.kf_mode = AOM_KF_DISABLED;
  config.rc_end_usage = variable_bitrate ? AOM_VBR : AOM_CBR;
  config.rc_target_bitrate =
      static_cast<unsigned int>(std::max(1, bitrate_bps / 1000));
  config.rc_min_quantizer = 10;
  config.rc_max_quantizer = static_cast<unsigned int>(max_quantizer);
  config.rc_undershoot_pct = 50;
  config.rc_overshoot_pct = 50;
  config.rc_buf_initial_sz =
      static_cast<unsigned int>(std::min(rate_buffer_ms, 600));
  config.rc_buf_optimal_sz =
      static_cast<unsigned int>(std::min(rate_buffer_ms, 600));
  config.rc_buf_sz = static_cast<unsigned int>(rate_buffer_ms);
  config.rc_dropframe_thresh = drop_frames ? 30 : 0;

  if (!Check(env, encoder.get(),
             aom_codec_enc_init(&encoder->codec, aom_codec_av1_cx(), &config, 0),
             "aom_codec_enc_init")) {
    return 0;
  }
  encoder->initialized = true;

  if (!SetControl(env, encoder.get(), AOME_SET_CPUUSED, cpu_used,
                  "AOME_SET_CPUUSED") ||
      !SetControl(env, encoder.get(), AV1E_SET_ENABLE_CDEF,
                  enable_cdef ? 1 : 0,
                  "AV1E_SET_ENABLE_CDEF") ||
      !SetControl(env, encoder.get(), AV1E_SET_AQ_MODE, aq_mode,
                  "AV1E_SET_AQ_MODE") ||
      !SetControl(env, encoder.get(), AOME_SET_TUNING,
                  tune_ssim ? AOM_TUNE_SSIM : AOM_TUNE_PSNR,
                  "AOME_SET_TUNING") ||
      !SetControl(env, encoder.get(), AV1E_SET_NOISE_SENSITIVITY,
                  noise_sensitivity, "AV1E_SET_NOISE_SENSITIVITY") ||
      !SetControl(env, encoder.get(), AOME_SET_MAX_INTRA_BITRATE_PCT, 300,
                  "AOME_SET_MAX_INTRA_BITRATE_PCT") ||
      !SetControl(env, encoder.get(), AV1E_SET_AUTO_TILES, 1,
                  "AV1E_SET_AUTO_TILES") ||
      !SetControl(env, encoder.get(), AV1E_SET_ROW_MT, 1,
                  "AV1E_SET_ROW_MT") ||
      !SetControl(env, encoder.get(), AV1E_SET_MAX_REFERENCE_FRAMES,
                  max_reference_frames,
                  "AV1E_SET_MAX_REFERENCE_FRAMES")) {
    return 0;
  }
  if (realtime_tool_profile &&
      !SetRealtimeToolProfile(env, encoder.get())) {
    return 0;
  }
  if (!quality_mode && !variable_bitrate &&
      (!SetControl(env, encoder.get(), AV1E_SET_MAX_CONSEC_FRAME_DROP_MS_CBR,
                   250, "AV1E_SET_MAX_CONSEC_FRAME_DROP_MS_CBR") ||
       !SetControl(env, encoder.get(), AV1E_SET_POSTENCODE_DROP_RTC, 1,
                   "AV1E_SET_POSTENCODE_DROP_RTC"))) {
    return 0;
  }

  return static_cast<jlong>(
      reinterpret_cast<intptr_t>(encoder.release()));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeEncode(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject i420_buffer,
    jlong presentation_time_us,
    jboolean force_key_frame,
    jint layer) {
  NativeEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized) {
    ThrowIllegalState(env, "Native libaom encoder is closed");
    return nullptr;
  }
  auto* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(i420_buffer));
  const jlong capacity = env->GetDirectBufferCapacity(i420_buffer);
  const size_t required =
      static_cast<size_t>(encoder->width) * encoder->height * 3 / 2;
  if (pixels == nullptr || capacity < static_cast<jlong>(required)) {
    ThrowIllegalState(env, "Native libaom input must be a direct I420 buffer");
    return nullptr;
  }

  aom_image_t image{};
  if (aom_img_wrap(&image, AOM_IMG_FMT_I420,
                   static_cast<unsigned int>(encoder->width),
                   static_cast<unsigned int>(encoder->height), 1, pixels) ==
      nullptr) {
    ThrowIllegalState(env, "Could not wrap native I420 input");
    return nullptr;
  }
  const unsigned long duration =
      static_cast<unsigned long>(1000000 / encoder->fps);
  aom_enc_frame_flags_t flags = force_key_frame ? AOM_EFLAG_FORCE_KF : 0;
  // Experimental temporal layering (off by default): explicit references under single-layer
  // rate control. libaom's SVC layer rate control picked QP 15-30 (27-72 KB frames) after forced
  // keyframes; per-frame AOM_EFLAG_NO_UPD_* flags are overwritten by libaom's own real-time
  // structure (a "droppable" frame still refreshed LAST and dropping three broke the M31 AV1
  // decoder); and a minimal LAST+GOLDEN structure cost ~2.5 VMAF even with every frame a
  // reference. This mirrors libaom's own one-layer structure
  // (av1_set_rtc_reference_structure_one_layer: LAST rotating through slots 0-5, GOLDEN in slot 6,
  // ALTREF lagging) on base frames, continuing from libaom's last refreshed slot, while layer 1
  // frames refresh nothing. layer < 0 (the default) keeps libaom's own structure and sets no
  // control; once explicit references start, only a new encoder returns to it.
  if (layer >= 0) {
    if (encoder->explicit_base < 0) encoder->explicit_base = encoder->frames_encoded;
    const int64_t base = encoder->explicit_base;
    const auto slot = [](int64_t index) {
      return static_cast<int>(((index % kRotatingSlots) + kRotatingSlots) % kRotatingSlots);
    };
    aom_svc_ref_frame_config_t refs{};
    const int last_slot = slot(base - 1);
    for (int i = 0; i < 7; ++i) {
      refs.ref_idx[i] = last_slot;
      refs.reference[i] = 0;
    }
    refs.reference[0] = 1;  // LAST: newest base frame
    refs.reference[3] = 1;  // GOLDEN
    refs.reference[6] = 1;  // ALTREF: base frame lagging LAST
    refs.ref_idx[3] = kGoldenSlot;
    refs.ref_idx[6] = slot(base - kAltRefLagBaseFrames);
    if (layer == 0 || force_key_frame) {
      refs.ref_idx[1] = slot(base);  // LAST2 maps the slot this frame refreshes
      refs.refresh[slot(base)] = 1;
      if (base % kGoldenRefreshBaseFrames == 0) refs.refresh[kGoldenSlot] = 1;
      encoder->explicit_base++;
    } else {
      flags |= AOM_EFLAG_NO_UPD_ENTROPY;
    }
    if (!Check(env, encoder,
               aom_codec_control(&encoder->codec, AV1E_SET_SVC_REF_FRAME_CONFIG,
                                 &refs),
               "AV1E_SET_SVC_REF_FRAME_CONFIG")) {
      return nullptr;
    }
  }
  // A forced keyframe mid-stream (a viewer joining) must not use a far lower QP than
  // the frames around it: libaom predicted QP 15-26 and produced 62-72 KB keyframes for
  // 400 kbps streams, which overloaded every link. Floor it near the current inter QP.
  const unsigned int base_min_quantizer = encoder->config.rc_min_quantizer;
  bool floored = false;
  if (force_key_frame && encoder->last_inter_quantizer >= 0) {
    const int floor_q = std::min(
        static_cast<int>(encoder->config.rc_max_quantizer),
        encoder->last_inter_quantizer - kKeyFrameQuantizerHeadroom);
    if (floor_q > static_cast<int>(base_min_quantizer)) {
      encoder->config.rc_min_quantizer = static_cast<unsigned int>(floor_q);
      floored = Check(env, encoder,
                      aom_codec_enc_config_set(&encoder->codec, &encoder->config),
                      "aom_codec_enc_config_set keyframe floor");
      if (!floored) return nullptr;
    }
  }
  const aom_codec_err_t encoded = aom_codec_encode(
      &encoder->codec, &image, presentation_time_us, duration, flags);
  if (floored) {
    encoder->config.rc_min_quantizer = base_min_quantizer;
    if (!Check(env, encoder,
               aom_codec_enc_config_set(&encoder->codec, &encoder->config),
               "aom_codec_enc_config_set restore")) {
      return nullptr;
    }
  }
  if (!Check(env, encoder, encoded, "aom_codec_encode")) {
    return nullptr;
  }
  encoder->frames_encoded++;

  return CollectPackets(env, encoder);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeFlush(
    JNIEnv* env,
    jclass,
    jlong handle) {
  NativeEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized) {
    ThrowIllegalState(env, "Native libaom encoder is closed");
    return nullptr;
  }
  if (!Check(env, encoder,
             aom_codec_encode(&encoder->codec, nullptr, 0, 0, 0),
             "aom_codec_encode flush")) {
    return nullptr;
  }
  return CollectPackets(env, encoder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetBitrate(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint bitrate_bps) {
  NativeEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized || bitrate_bps <= 0) {
    ThrowIllegalState(env, "Invalid native libaom bitrate update");
    return;
  }
  encoder->config.rc_target_bitrate =
      static_cast<unsigned int>(std::max(1, bitrate_bps / 1000));
  Check(env, encoder,
        aom_codec_enc_config_set(&encoder->codec, &encoder->config),
        "aom_codec_enc_config_set");

}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetCpuUsed(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint cpu_used) {
  NativeEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized) {
    ThrowIllegalState(env, "Invalid native libaom speed update");
    return;
  }
  SetControl(env, encoder, AOME_SET_CPUUSED, cpu_used, "AOME_SET_CPUUSED");
}

// Experimental temporal layering: a re-created encoder floors its first (key) frame near the QP
// its predecessor used.
extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetKeyFrameQuantizerReference(
    JNIEnv*,
    jclass,
    jlong handle,
    jint quantizer) {
  NativeEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || quantizer < 0 || quantizer > 63) return;
  encoder->last_inter_quantizer = quantizer;
}

// Caps one inter frame at a percentage of the average frame (0 = libaom default, unlimited).
extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeSetMaxInterBitratePct(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint percent) {
  NativeEncoder* encoder = FromHandle(handle);
  if (encoder == nullptr || !encoder->initialized || percent < 0) return;
  SetControl(env, encoder, AV1E_SET_MAX_INTER_BITRATE_PCT, percent,
             "AV1E_SET_MAX_INTER_BITRATE_PCT");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bluetoothtest_NativeAomBridge_nativeClose(
    JNIEnv*,
    jclass,
    jlong handle) {
  delete FromHandle(handle);
}
