#include <jni.h>
#include <android/log.h>
#include <libyuv.h>
#include <vector>

namespace {
const char* kTag = "YuvBridge";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_tongxing_util_YuvConverter_convertToArgb(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray yuv_data,
    jint width,
    jint height,
    jint stride,
    jint slice_height,
    jint color_format,
    jobject out_argb_buffer) {
  if (!yuv_data || !out_argb_buffer || width <= 0 || height <= 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Invalid params");
    return JNI_FALSE;
  }

  auto* out_argb = static_cast<uint8_t*>(env->GetDirectBufferAddress(out_argb_buffer));
  if (!out_argb) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Output buffer is not direct");
    return JNI_FALSE;
  }

  jsize data_size = env->GetArrayLength(yuv_data);
  jbyte* data_ptr = env->GetByteArrayElements(yuv_data, nullptr);
  if (!data_ptr) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Unable to access yuv array");
    return JNI_FALSE;
  }

  const uint8_t* data = reinterpret_cast<const uint8_t*>(data_ptr);
  int y_stride = stride > 0 ? stride : width;
  int uv_stride = y_stride / 2;
  int plane_height = slice_height > 0 ? slice_height : height;
  int y_plane_size = y_stride * plane_height;
  const int min_plane_height = height > 0 ? height : plane_height;
  const int min_expected = width * height * 3 / 2;
  if (data_size >= min_expected && data_size < y_plane_size + y_stride * (plane_height / 2)) {
    y_stride = width;
    uv_stride = y_stride / 2;
    plane_height = height;
    y_plane_size = y_stride * plane_height;
  }

  bool ok = false;
  if (color_format == 0x13) {  // COLOR_FormatYUV420Planar (deprecated constant)
    int u_plane_size = uv_stride * (plane_height / 2);
    if (data_size < y_plane_size + u_plane_size * 2 && plane_height > min_plane_height) {
      plane_height = min_plane_height;
      y_plane_size = y_stride * plane_height;
      u_plane_size = uv_stride * (plane_height / 2);
    }
    if (data_size >= y_plane_size + u_plane_size * 2) {
      const uint8_t* y_plane = data;
      const uint8_t* u_plane = data + y_plane_size;
      const uint8_t* v_plane = data + y_plane_size + u_plane_size;
      ok = libyuv::I420ToARGB(
          y_plane, y_stride,
          u_plane, uv_stride,
          v_plane, uv_stride,
          out_argb, width * 4,
          width, height) == 0;
    }
  } else if (color_format == 0x15) {  // COLOR_FormatYUV420SemiPlanar (NV21)
    int uv_plane_size = y_stride * (plane_height / 2);
    if (data_size < y_plane_size + uv_plane_size && plane_height > min_plane_height) {
      plane_height = min_plane_height;
      y_plane_size = y_stride * plane_height;
      uv_plane_size = y_stride * (plane_height / 2);
    }
    const int uv_offset_height = y_stride * height;
    const int uv_offset_slice = y_plane_size;
    const int uv_offset = (data_size >= uv_offset_height + (y_stride * (height / 2)))
                              ? uv_offset_height
                              : uv_offset_slice;
    if (data_size >= uv_offset + y_stride * (height / 2)) {
      const uint8_t* y_plane = data;
      const uint8_t* vu_plane = data + uv_offset;
      ok = libyuv::NV21ToARGB(
          y_plane, y_stride,
          vu_plane, y_stride,
          out_argb, width * 4,
          width, height) == 0;
    }
  } else {  // treat as NV12 for semi-planar/flexible
    int uv_plane_size = y_stride * (plane_height / 2);
    if (data_size < y_plane_size + uv_plane_size && plane_height > min_plane_height) {
      plane_height = min_plane_height;
      y_plane_size = y_stride * plane_height;
      uv_plane_size = y_stride * (plane_height / 2);
    }
    if (data_size >= y_plane_size + uv_plane_size) {
      const uint8_t* y_plane = data;
      const uint8_t* uv_plane = data + y_plane_size;
      ok = libyuv::NV12ToARGB(
          y_plane, y_stride,
          uv_plane, y_stride,
          out_argb, width * 4,
          width, height) == 0;
    }
  }

  env->ReleaseByteArrayElements(yuv_data, data_ptr, JNI_ABORT);
  if (!ok) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "libyuv conversion failed format=%d", color_format);
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_tongxing_util_YuvConverter_convertToRgb24Scaled(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray yuv_data,
    jint width,
    jint height,
    jint stride,
    jint slice_height,
    jint color_format,
    jint dst_width,
    jint dst_height,
    jobject out_rgb_buffer) {
  if (!yuv_data || !out_rgb_buffer || width <= 0 || height <= 0 ||
      dst_width <= 0 || dst_height <= 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Invalid params");
    return JNI_FALSE;
  }

  auto* out_rgb = static_cast<uint8_t*>(env->GetDirectBufferAddress(out_rgb_buffer));
  if (!out_rgb) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Output buffer is not direct");
    return JNI_FALSE;
  }

  jsize data_size = env->GetArrayLength(yuv_data);
  jbyte* data_ptr = env->GetByteArrayElements(yuv_data, nullptr);
  if (!data_ptr) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Unable to access yuv array");
    return JNI_FALSE;
  }

  const uint8_t* data = reinterpret_cast<const uint8_t*>(data_ptr);
  int y_stride = stride > 0 ? stride : width;
  int uv_stride = y_stride / 2;
  int plane_height = slice_height > 0 ? slice_height : height;
  int y_plane_size = y_stride * plane_height;
  const int min_plane_height = height > 0 ? height : plane_height;
  const int min_expected = width * height * 3 / 2;
  if (data_size >= min_expected && data_size < y_plane_size + y_stride * (plane_height / 2)) {
    y_stride = width;
    uv_stride = y_stride / 2;
    plane_height = height;
    y_plane_size = y_stride * plane_height;
  }

  static thread_local std::vector<uint8_t> tmp_y;
  static thread_local std::vector<uint8_t> tmp_u;
  static thread_local std::vector<uint8_t> tmp_v;
  static thread_local std::vector<uint8_t> tmp_uv;
  static thread_local std::vector<uint8_t> tmp_y2;
  static thread_local std::vector<uint8_t> tmp_u2;
  static thread_local std::vector<uint8_t> tmp_v2;

  bool ok = false;
  if (color_format == 0x13) {  // COLOR_FormatYUV420Planar
    int u_plane_size = uv_stride * (plane_height / 2);
    if (data_size < y_plane_size + u_plane_size * 2 && plane_height > min_plane_height) {
      plane_height = min_plane_height;
      y_plane_size = y_stride * plane_height;
      u_plane_size = uv_stride * (plane_height / 2);
    }
    if (data_size >= y_plane_size + u_plane_size * 2) {
      const uint8_t* y_plane = data;
      const uint8_t* u_plane = data + y_plane_size;
      const uint8_t* v_plane = data + y_plane_size + u_plane_size;

      const int dst_y_stride = dst_width;
      const int dst_uv_stride = dst_width / 2;
      const int dst_y_size = dst_y_stride * dst_height;
      const int dst_uv_size = dst_uv_stride * (dst_height / 2);
      tmp_y.resize(dst_y_size);
      tmp_u.resize(dst_uv_size);
      tmp_v.resize(dst_uv_size);

      ok = libyuv::I420Scale(
               y_plane, y_stride,
               u_plane, uv_stride,
               v_plane, uv_stride,
               width, height,
               tmp_y.data(), dst_y_stride,
               tmp_u.data(), dst_uv_stride,
               tmp_v.data(), dst_uv_stride,
               dst_width, dst_height,
               libyuv::kFilterBilinear) == 0;
      if (ok) {
        ok = libyuv::I420ToRGB24(
                 tmp_y.data(), dst_y_stride,
                 tmp_u.data(), dst_uv_stride,
                 tmp_v.data(), dst_uv_stride,
                 out_rgb, dst_width * 3,
                 dst_width, dst_height) == 0;
      }
    }
  } else if (color_format == 0x15) {  // COLOR_FormatYUV420SemiPlanar (NV21)
    int uv_plane_size = y_stride * (plane_height / 2);
    if (data_size < y_plane_size + uv_plane_size && plane_height > min_plane_height) {
      plane_height = min_plane_height;
      y_plane_size = y_stride * plane_height;
      uv_plane_size = y_stride * (plane_height / 2);
    }
    const int uv_offset_height = y_stride * height;
    const int uv_offset_slice = y_plane_size;
    const int uv_offset = (data_size >= uv_offset_height + (y_stride * (height / 2)))
                              ? uv_offset_height
                              : uv_offset_slice;
    if (data_size >= uv_offset + y_stride * (height / 2)) {
      const uint8_t* y_plane = data;
      const uint8_t* vu_plane = data + uv_offset;

      const int src_y_stride = width;
      const int src_uv_stride = width / 2;
      const int src_y_size = src_y_stride * height;
      const int src_uv_size = src_uv_stride * (height / 2);
      tmp_y.resize(src_y_size);
      tmp_u.resize(src_uv_size);
      tmp_v.resize(src_uv_size);

      ok = libyuv::NV21ToI420(
               y_plane, y_stride,
               vu_plane, y_stride,
               tmp_y.data(), src_y_stride,
               tmp_u.data(), src_uv_stride,
               tmp_v.data(), src_uv_stride,
               width, height) == 0;
      if (ok) {
        const int dst_y_stride = dst_width;
        const int dst_uv_stride = dst_width / 2;
        const int dst_y_size = dst_y_stride * dst_height;
        const int dst_uv_size = dst_uv_stride * (dst_height / 2);
        tmp_y2.resize(dst_y_size);
        tmp_u2.resize(dst_uv_size);
        tmp_v2.resize(dst_uv_size);
        ok = libyuv::I420Scale(
                 tmp_y.data(), src_y_stride,
                 tmp_u.data(), src_uv_stride,
                 tmp_v.data(), src_uv_stride,
                 width, height,
                 tmp_y2.data(), dst_y_stride,
                 tmp_u2.data(), dst_uv_stride,
                 tmp_v2.data(), dst_uv_stride,
                 dst_width, dst_height,
                 libyuv::kFilterBilinear) == 0;
        if (ok) {
          ok = libyuv::I420ToRGB24(
                   tmp_y2.data(), dst_y_stride,
                   tmp_u2.data(), dst_uv_stride,
                   tmp_v2.data(), dst_uv_stride,
                   out_rgb, dst_width * 3,
                   dst_width, dst_height) == 0;
        }
      }
    }
  } else {  // treat as NV12 for semi-planar/flexible
    int uv_plane_size = y_stride * (plane_height / 2);
    if (data_size < y_plane_size + uv_plane_size && plane_height > min_plane_height) {
      plane_height = min_plane_height;
      y_plane_size = y_stride * plane_height;
      uv_plane_size = y_stride * (plane_height / 2);
    }
    if (data_size >= y_plane_size + uv_plane_size) {
      const uint8_t* y_plane = data;
      const uint8_t* uv_plane = data + y_plane_size;

      const int dst_y_stride = dst_width;
      const int dst_uv_stride = dst_width;
      const int dst_y_size = dst_y_stride * dst_height;
      const int dst_uv_size = dst_uv_stride * (dst_height / 2);
      tmp_y.resize(dst_y_size);
      tmp_uv.resize(dst_uv_size);

      ok = libyuv::NV12Scale(
               y_plane, y_stride,
               uv_plane, y_stride,
               width, height,
               tmp_y.data(), dst_y_stride,
               tmp_uv.data(), dst_uv_stride,
               dst_width, dst_height,
               libyuv::kFilterBilinear) == 0;
      if (ok) {
        ok = libyuv::NV12ToRGB24(
                 tmp_y.data(), dst_y_stride,
                 tmp_uv.data(), dst_uv_stride,
                 out_rgb, dst_width * 3,
                 dst_width, dst_height) == 0;
      }
    }
  }

  env->ReleaseByteArrayElements(yuv_data, data_ptr, JNI_ABORT);
  if (!ok) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "libyuv rgb24 scale failed format=%d", color_format);
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_tongxing_util_YuvConverter_convertYuv420ToArgb(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject y_plane,
    jint y_row_stride,
    jobject u_plane,
    jint u_row_stride,
    jint u_pixel_stride,
    jobject v_plane,
    jint v_row_stride,
    jint v_pixel_stride,
    jint width,
    jint height,
    jobject out_argb_buffer) {
  if (!y_plane || !u_plane || !v_plane || !out_argb_buffer || width <= 0 || height <= 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Invalid params for YUV420 ARGB");
    return JNI_FALSE;
  }
  if (u_pixel_stride != v_pixel_stride) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "UV pixel stride mismatch %d vs %d",
                        u_pixel_stride, v_pixel_stride);
    return JNI_FALSE;
  }

  auto* y_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(y_plane));
  auto* u_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(u_plane));
  auto* v_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(v_plane));
  auto* out_argb = static_cast<uint8_t*>(env->GetDirectBufferAddress(out_argb_buffer));
  if (!y_ptr || !u_ptr || !v_ptr || !out_argb) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Direct buffer address failed");
    return JNI_FALSE;
  }

  static thread_local std::vector<uint8_t> tmp_y;
  static thread_local std::vector<uint8_t> tmp_u;
  static thread_local std::vector<uint8_t> tmp_v;
  const int dst_y_stride = width;
  const int dst_uv_stride = width / 2;
  const int dst_y_size = dst_y_stride * height;
  const int dst_uv_size = dst_uv_stride * (height / 2);
  tmp_y.resize(dst_y_size);
  tmp_u.resize(dst_uv_size);
  tmp_v.resize(dst_uv_size);

  bool ok = libyuv::Android420ToI420(
                y_ptr, y_row_stride,
                u_ptr, u_row_stride,
                v_ptr, v_row_stride,
                u_pixel_stride,
                tmp_y.data(), dst_y_stride,
                tmp_u.data(), dst_uv_stride,
                tmp_v.data(), dst_uv_stride,
                width, height) == 0;
  if (ok) {
    ok = libyuv::I420ToARGB(
             tmp_y.data(), dst_y_stride,
             tmp_u.data(), dst_uv_stride,
             tmp_v.data(), dst_uv_stride,
             out_argb, width * 4,
             width, height) == 0;
  }
  if (!ok) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Android420->ARGB failed");
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_tongxing_util_YuvConverter_convertYuv420ToRgb24Scaled(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject y_plane,
    jint y_row_stride,
    jobject u_plane,
    jint u_row_stride,
    jint u_pixel_stride,
    jobject v_plane,
    jint v_row_stride,
    jint v_pixel_stride,
    jint width,
    jint height,
    jint dst_width,
    jint dst_height,
    jobject out_rgb_buffer) {
  if (!y_plane || !u_plane || !v_plane || !out_rgb_buffer || width <= 0 || height <= 0 ||
      dst_width <= 0 || dst_height <= 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Invalid params for YUV420 RGB24");
    return JNI_FALSE;
  }
  if (u_pixel_stride != v_pixel_stride) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "UV pixel stride mismatch %d vs %d",
                        u_pixel_stride, v_pixel_stride);
    return JNI_FALSE;
  }

  auto* y_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(y_plane));
  auto* u_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(u_plane));
  auto* v_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(v_plane));
  auto* out_rgb = static_cast<uint8_t*>(env->GetDirectBufferAddress(out_rgb_buffer));
  if (!y_ptr || !u_ptr || !v_ptr || !out_rgb) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Direct buffer address failed");
    return JNI_FALSE;
  }

  static thread_local std::vector<uint8_t> tmp_y;
  static thread_local std::vector<uint8_t> tmp_u;
  static thread_local std::vector<uint8_t> tmp_v;
  static thread_local std::vector<uint8_t> tmp_y2;
  static thread_local std::vector<uint8_t> tmp_u2;
  static thread_local std::vector<uint8_t> tmp_v2;

  const int src_y_stride = width;
  const int src_uv_stride = width / 2;
  const int src_y_size = src_y_stride * height;
  const int src_uv_size = src_uv_stride * (height / 2);
  tmp_y.resize(src_y_size);
  tmp_u.resize(src_uv_size);
  tmp_v.resize(src_uv_size);

  bool ok = libyuv::Android420ToI420(
                y_ptr, y_row_stride,
                u_ptr, u_row_stride,
                v_ptr, v_row_stride,
                u_pixel_stride,
                tmp_y.data(), src_y_stride,
                tmp_u.data(), src_uv_stride,
                tmp_v.data(), src_uv_stride,
                width, height) == 0;
  if (ok) {
    const int dst_y_stride = dst_width;
    const int dst_uv_stride = dst_width / 2;
    const int dst_y_size = dst_y_stride * dst_height;
    const int dst_uv_size = dst_uv_stride * (dst_height / 2);
    tmp_y2.resize(dst_y_size);
    tmp_u2.resize(dst_uv_size);
    tmp_v2.resize(dst_uv_size);
    ok = libyuv::I420Scale(
             tmp_y.data(), src_y_stride,
             tmp_u.data(), src_uv_stride,
             tmp_v.data(), src_uv_stride,
             width, height,
             tmp_y2.data(), dst_y_stride,
             tmp_u2.data(), dst_uv_stride,
             tmp_v2.data(), dst_uv_stride,
             dst_width, dst_height,
             libyuv::kFilterBilinear) == 0;
    if (ok) {
      ok = libyuv::I420ToRGB24(
               tmp_y2.data(), dst_y_stride,
               tmp_u2.data(), dst_uv_stride,
               tmp_v2.data(), dst_uv_stride,
               out_rgb, dst_width * 3,
               dst_width, dst_height) == 0;
    }
  }
  if (!ok) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "Android420->RGB24 failed");
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}
