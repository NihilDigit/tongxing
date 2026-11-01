#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstdint>
#include <vector>
#include <thread>
#include <cmath>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "qnnops", __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_qnninferencesdk_core_NativeOps_packArgbToRgbF(
        JNIEnv* env, jobject /*thiz*/, jobject bitmap, jobject dstBuf, jint width, jint height) {
    if (!dstBuf) return JNI_FALSE;
    void* dstPtr = env->GetDirectBufferAddress(dstBuf);
    if (!dstPtr) { LOGE("dst buffer not direct"); return JNI_FALSE; }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != width || info.height != height) {
        // Only support fast-path when sizes match and format is RGBA_8888
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;

    const uint32_t* src = reinterpret_cast<uint32_t*>(pixels);
    float* out = reinterpret_cast<float*>(dstPtr);
    const int wh = width * height;
    for (int i = 0; i < wh; ++i) {
        uint32_t p = src[i];
        float r = ((p >> 16) & 0xFF) / 255.0f;
        float g = ((p >> 8) & 0xFF) / 255.0f;
        float b = (p & 0xFF) / 255.0f;
        // NHWC: R,G,B interleaved
        *out++ = r; *out++ = g; *out++ = b;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_lightwaysafetycore_util_NativeImageOps_upsampleDepthBilinear(
        JNIEnv* env, jobject /*thiz*/, jfloatArray src, jint mW, jint mH,
        jint fW, jint fH, jfloat scale, jint padX, jint padY, jfloatArray dst) {
    if (!src || !dst) return JNI_FALSE;
    jboolean isCopy1 = JNI_FALSE, isCopy2 = JNI_FALSE;
    jfloat* in = env->GetFloatArrayElements(src, &isCopy1);
    jfloat* out = env->GetFloatArrayElements(dst, &isCopy2);
    if (!in || !out) {
        if (in) env->ReleaseFloatArrayElements(src, in, 0);
        if (out) env->ReleaseFloatArrayElements(dst, out, 0);
        return JNI_FALSE;
    }
    const int fw = fW, fh = fH, mw = mW, mh = mH;
    // Precompute mapping arrays to reduce per-pixel math
    std::vector<int> x0v(fw), x1v(fw); std::vector<float> dxv(fw);
    for (int fx = 0; fx < fw; ++fx) {
        float mx = fx * scale + padX;
        int x0 = (int) mx; if (x0 < 0) x0 = 0; if (x0 >= mw) x0 = mw - 1;
        int x1 = x0 + 1; if (x1 >= mw) x1 = mw - 1;
        x0v[fx] = x0; x1v[fx] = x1; dxv[fx] = mx - x0;
    }
    std::vector<int> y0v(fh), y1v(fh); std::vector<float> dyv(fh);
    for (int fy = 0; fy < fh; ++fy) {
        float my = fy * scale + padY;
        int y0 = (int) my; if (y0 < 0) y0 = 0; if (y0 >= mh) y0 = mh - 1;
        int y1 = y0 + 1; if (y1 >= mh) y1 = mh - 1;
        y0v[fy] = y0; y1v[fy] = y1; dyv[fy] = my - y0;
    }
    for (int fy = 0; fy < fh; ++fy) {
        int y0 = y0v[fy]; int y1 = y1v[fy]; float dy = dyv[fy];
        int row0 = y0 * mw; int row1 = y1 * mw; int outRow = fy * fw;
        for (int fx = 0; fx < fw; ++fx) {
            int x0 = x0v[fx]; int x1 = x1v[fx]; float dx = dxv[fx];
            float v00 = in[row0 + x0];
            float v10 = in[row0 + x1];
            float v01 = in[row1 + x0];
            float v11 = in[row1 + x1];
            float val;
            if (std::isnan(v00) || std::isnan(v10) || std::isnan(v01) || std::isnan(v11)) {
                val = NAN;
            } else {
                float w00 = (1 - dx) * (1 - dy);
                float w10 = dx * (1 - dy);
                float w01 = (1 - dx) * dy;
                float w11 = dx * dy;
                val = v00 * w00 + v10 * w10 + v01 * w01 + v11 * w11;
            }
            out[outRow + fx] = val;
        }
    }
    env->ReleaseFloatArrayElements(src, in, 0);
    env->ReleaseFloatArrayElements(dst, out, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_lightwaysafetycore_util_NativeImageOps_normalizeDepthMeters(
        JNIEnv* env, jobject /*thiz*/, jfloatArray rel, jint width, jint height,
        jfloat a, jfloat b, jfloat minM, jfloat maxM, jfloatArray dst) {
    if (!rel || !dst) return JNI_FALSE;
    jboolean isCopy1 = JNI_FALSE, isCopy2 = JNI_FALSE;
    jfloat* in = env->GetFloatArrayElements(rel, &isCopy1);
    jfloat* out = env->GetFloatArrayElements(dst, &isCopy2);
    if (!in || !out) {
        if (in) env->ReleaseFloatArrayElements(rel, in, 0);
        if (out) env->ReleaseFloatArrayElements(dst, out, 0);
        return JNI_FALSE;
    }
    const int n = width * height;
    for (int i = 0; i < n; ++i) {
        float r = in[i];
        if (std::isfinite(r) && r > 0.f) {
            float d = a / (r + b);
            if (d < minM) d = minM; else if (d > maxM) d = maxM;
            out[i] = d;
        } else {
            out[i] = NAN;
        }
    }
    env->ReleaseFloatArrayElements(rel, in, 0);
    env->ReleaseFloatArrayElements(dst, out, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_qnninferencesdk_core_NativeOps_packArgbToRgbU8(
        JNIEnv* env, jobject /*thiz*/, jobject bitmap, jobject dstBuf, jint width, jint height) {
    if (!dstBuf) return JNI_FALSE;
    void* dstPtr = env->GetDirectBufferAddress(dstBuf);
    if (!dstPtr) { LOGE("dst buffer not direct"); return JNI_FALSE; }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != width || info.height != height) {
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;

    const uint32_t* src = reinterpret_cast<uint32_t*>(pixels);
    uint8_t* out = reinterpret_cast<uint8_t*>(dstPtr);
    const int wh = width * height;
    for (int i = 0; i < wh; ++i) {
        uint32_t p = src[i];
        uint8_t r = (p >> 16) & 0xFF;
        uint8_t g = (p >> 8) & 0xFF;
        uint8_t b = p & 0xFF;
        *out++ = r; *out++ = g; *out++ = b;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_qnninferencesdk_core_NativeOps_argmaxU8NHWC(
        JNIEnv* env, jobject, jbyteArray arr, jint h, jint w, jint c, jbyteArray out) {
    if (!arr || !out) return JNI_FALSE;
    jboolean is1 = JNI_FALSE, is2 = JNI_FALSE;
    jbyte* a = env->GetByteArrayElements(arr, &is1);
    jbyte* o = env->GetByteArrayElements(out, &is2);
    if (!a || !o) {
        if (a) env->ReleaseByteArrayElements(arr, a, 0);
        if (o) env->ReleaseByteArrayElements(out, o, 0);
        return JNI_FALSE;
    }
    int base = 0;
    const int hw = h * w;
    for (int i = 0; i < hw; ++i) {
        int maxIdx = 0;
        int maxVal = (a[base] & 0xFF);
        for (int cls = 1; cls < c; ++cls) {
            int v = (a[base + cls] & 0xFF);
            if (v > maxVal) { maxVal = v; maxIdx = cls; }
        }
        o[i] = (jbyte) maxIdx;
        base += c;
    }
    env->ReleaseByteArrayElements(arr, a, 0);
    env->ReleaseByteArrayElements(out, o, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_qnninferencesdk_core_NativeOps_argmaxU8NCHW(
        JNIEnv* env, jobject, jbyteArray arr, jint h, jint w, jint c, jbyteArray out) {
    if (!arr || !out) return JNI_FALSE;
    jboolean is1 = JNI_FALSE, is2 = JNI_FALSE;
    jbyte* a = env->GetByteArrayElements(arr, &is1);
    jbyte* o = env->GetByteArrayElements(out, &is2);
    if (!a || !o) {
        if (a) env->ReleaseByteArrayElements(arr, a, 0);
        if (o) env->ReleaseByteArrayElements(out, o, 0);
        return JNI_FALSE;
    }
    const int hw = h * w;
    int idx = 0;
    for (int yy = 0; yy < h; ++yy) {
        for (int xx = 0; xx < w; ++xx) {
            int maxIdx = 0;
            int maxVal = (a[(0 * hw) + (yy * w + xx)] & 0xFF);
            for (int cls = 1; cls < c; ++cls) {
                int v = (a[(cls * hw) + (yy * w + xx)] & 0xFF);
                if (v > maxVal) { maxVal = v; maxIdx = cls; }
            }
            o[idx++] = (jbyte) maxIdx;
        }
    }
    env->ReleaseByteArrayElements(arr, a, 0);
    env->ReleaseByteArrayElements(out, o, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_qnninferencesdk_core_NativeOps_argmaxF32NHWC(
        JNIEnv* env, jobject, jfloatArray arr, jint h, jint w, jint c, jbyteArray out) {
    if (!arr || !out) return JNI_FALSE;
    jboolean is1 = JNI_FALSE, is2 = JNI_FALSE;
    jfloat* a = env->GetFloatArrayElements(arr, &is1);
    jbyte* o = env->GetByteArrayElements(out, &is2);
    if (!a || !o) {
        if (a) env->ReleaseFloatArrayElements(arr, a, 0);
        if (o) env->ReleaseByteArrayElements(out, o, 0);
        return JNI_FALSE;
    }
    int base = 0;
    const int hw = h * w;
    for (int i = 0; i < hw; ++i) {
        int maxIdx = 0;
        float maxVal = a[base];
        for (int cls = 1; cls < c; ++cls) {
            float v = a[base + cls];
            if (v > maxVal) { maxVal = v; maxIdx = cls; }
        }
        o[i] = (jbyte) maxIdx;
        base += c;
    }
    env->ReleaseFloatArrayElements(arr, a, 0);
    env->ReleaseByteArrayElements(out, o, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_qnninferencesdk_core_NativeOps_argmaxF32NCHW(
        JNIEnv* env, jobject, jfloatArray arr, jint h, jint w, jint c, jbyteArray out) {
    if (!arr || !out) return JNI_FALSE;
    jboolean is1 = JNI_FALSE, is2 = JNI_FALSE;
    jfloat* a = env->GetFloatArrayElements(arr, &is1);
    jbyte* o = env->GetByteArrayElements(out, &is2);
    if (!a || !o) {
        if (a) env->ReleaseFloatArrayElements(arr, a, 0);
        if (o) env->ReleaseByteArrayElements(out, o, 0);
        return JNI_FALSE;
    }
    const int hw = h * w;
    int idx = 0;
    for (int yy = 0; yy < h; ++yy) {
        for (int xx = 0; xx < w; ++xx) {
            int maxIdx = 0;
            float maxVal = a[(0 * hw) + (yy * w + xx)];
            for (int cls = 1; cls < c; ++cls) {
                float v = a[(cls * hw) + (yy * w + xx)];
                if (v > maxVal) { maxVal = v; maxIdx = cls; }
            }
            o[idx++] = (jbyte) maxIdx;
        }
    }
    env->ReleaseFloatArrayElements(arr, a, 0);
    env->ReleaseByteArrayElements(out, o, 0);
    return JNI_TRUE;
}
