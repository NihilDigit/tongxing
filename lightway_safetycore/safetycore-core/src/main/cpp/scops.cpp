#include <jni.h>
#include <cmath>
#include <cstdint>
#include <vector>
#include <algorithm>
#include <limits>
#include <android/log.h>

#define LOG_TAG "scops"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static inline float bilinearSample(
        const float* src, int sw, int sh,
        float mx, float my) {
    // Clamp integer base indices inside valid range
    int x0 = (int)mx; if (x0 < 0) x0 = 0; if (x0 > sw - 1) x0 = sw - 1;
    int y0 = (int)my; if (y0 < 0) y0 = 0; if (y0 > sh - 1) y0 = sh - 1;
    int x1 = x0 + 1; if (x1 > sw - 1) x1 = sw - 1;
    int y1 = y0 + 1; if (y1 > sh - 1) y1 = sh - 1;

    float dx = mx - (float)x0;
    float dy = my - (float)y0;

    const float v00 = src[y0 * sw + x0];
    const float v10 = src[y0 * sw + x1];
    const float v01 = src[y1 * sw + x0];
    const float v11 = src[y1 * sw + x1];

    // If any is NaN -> result NaN
    if (std::isnan(v00) || std::isnan(v10) || std::isnan(v01) || std::isnan(v11)) {
        return std::numeric_limits<float>::quiet_NaN();
    }

    const float w00 = (1.f - dx) * (1.f - dy);
    const float w10 = dx * (1.f - dy);
    const float w01 = (1.f - dx) * dy;
    const float w11 = dx * dy;
    return v00 * w00 + v10 * w10 + v01 * w01 + v11 * w11;
}

// Convert YUV420 (Image.Plane buffers) to ARGB8888
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_yuv420ToArgb(
        JNIEnv* env, jclass,
        jobject yBuf, jint yRowStride,
        jobject uBuf, jint uRowStride,
        jobject vBuf, jint vRowStride,
        jint pixelStrideUV,
        jint width, jint height,
        jintArray outArgb) {
    if (!yBuf || !uBuf || !vBuf || !outArgb) return JNI_FALSE;
    auto* yPtr = (uint8_t*)env->GetDirectBufferAddress(yBuf);
    auto* uPtr = (uint8_t*)env->GetDirectBufferAddress(uBuf);
    auto* vPtr = (uint8_t*)env->GetDirectBufferAddress(vBuf);
    if (!yPtr || !uPtr || !vPtr) return JNI_FALSE;

    jsize outLen = env->GetArrayLength(outArgb);
    if (outLen < width * height) return JNI_FALSE;
    jboolean isCopy = JNI_FALSE;
    jint* out = env->GetIntArrayElements(outArgb, &isCopy);

    for (int y = 0; y < height; ++y) {
        const uint8_t* yRow = yPtr + y * yRowStride;
        const uint8_t* uRow = uPtr + (y / 2) * uRowStride;
        const uint8_t* vRow = vPtr + (y / 2) * vRowStride;
        for (int x = 0; x < width; ++x) {
            int yVal = yRow[x] & 0xFF;
            int uVal = uRow[(x / 2) * pixelStrideUV] & 0xFF;
            int vVal = vRow[(x / 2) * pixelStrideUV] & 0xFF;
            float yf = (float)yVal;
            float uf = (float)uVal - 128.f;
            float vf = (float)vVal - 128.f;
            float r = yf + 1.402f * vf;
            float g = yf - 0.344f * uf - 0.714f * vf;
            float b = yf + 1.772f * uf;
            int ri = (int)fminf(fmaxf(r, 0.f), 255.f);
            int gi = (int)fminf(fmaxf(g, 0.f), 255.f);
            int bi = (int)fminf(fmaxf(b, 0.f), 255.f);
            out[y * width + x] = (0xFF << 24) | (ri << 16) | (gi << 8) | bi;
        }
    }

    env->ReleaseIntArrayElements(outArgb, out, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_upsampleDepthBilinear(
        JNIEnv* env, jclass,
        jfloatArray jSrc,
        jint modelW,
        jint modelH,
        jint frameW,
        jint frameH,
        jfloat scale,
        jint padX,
        jint padY,
        jfloatArray jDst) {
    if (!jSrc || !jDst) return JNI_FALSE;
    const jsize srcLen = env->GetArrayLength(jSrc);
    const jsize dstLen = env->GetArrayLength(jDst);
    if (srcLen < modelW * modelH || dstLen < frameW * frameH) return JNI_FALSE;

    jboolean isCopySrc = JNI_FALSE;
    jboolean isCopyDst = JNI_FALSE;
    const float* src = env->GetFloatArrayElements(jSrc, &isCopySrc);
    float* dst = env->GetFloatArrayElements(jDst, &isCopyDst);

    // Precompute per-row base y mapping
    for (int fy = 0; fy < frameH; ++fy) {
        const float my = fy * scale + (float)padY;
        for (int fx = 0; fx < frameW; ++fx) {
            const float mx = fx * scale + (float)padX;
            const float v = bilinearSample(src, modelW, modelH, mx, my);
            dst[fy * frameW + fx] = v;
        }
    }

    env->ReleaseFloatArrayElements(jSrc, const_cast<float*>(src), JNI_ABORT);
    env->ReleaseFloatArrayElements(jDst, dst, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_normalizeDepthMeters(
        JNIEnv* env, jclass,
        jfloatArray jRel,
        jint width,
        jint height,
        jfloat a,
        jfloat b,
        jfloat minM,
        jfloat maxM,
        jfloatArray jDst) {
    if (!jRel || !jDst) return JNI_FALSE;
    const jsize relLen = env->GetArrayLength(jRel);
    const jsize dstLen = env->GetArrayLength(jDst);
    if (relLen < width * height || dstLen < width * height) return JNI_FALSE;

    jboolean isCopyRel = JNI_FALSE;
    jboolean isCopyDst = JNI_FALSE;
    const float* rel = env->GetFloatArrayElements(jRel, &isCopyRel);
    float* dst = env->GetFloatArrayElements(jDst, &isCopyDst);

    const int N = width * height;
    for (int i = 0; i < N; ++i) {
        const float r = rel[i];
        if (std::isfinite(r) && r > 0.f) {
            float d = a / (r + b);
            if (d < minM) d = minM; else if (d > maxM) d = maxM;
            dst[i] = d;
        } else {
            dst[i] = std::numeric_limits<float>::quiet_NaN();
        }
    }

    env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
    env->ReleaseFloatArrayElements(jDst, dst, 0);
    return JNI_TRUE;
}

// Build aligned segmentation (nearest) and walkable mask in one pass.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_buildAlignedSegAndWalkMask(
        JNIEnv* env, jclass,
        jbyteArray jClasses,
        jint modelW,
        jint modelH,
        jint frameW,
        jint frameH,
        jfloat scale,
        jint padX,
        jint padY,
        jintArray jWalkableIds,
        jbyteArray jOutSeg,
        jbyteArray jOutMask) {
    if (!jClasses || !jOutSeg || !jOutMask) return JNI_FALSE;
    const jsize cLen = env->GetArrayLength(jClasses);
    const jsize outLen = env->GetArrayLength(jOutSeg);
    if (cLen < modelW * modelH || outLen < frameW * frameH) return JNI_FALSE;

    jboolean isCopyC = JNI_FALSE;
    jboolean isCopyS = JNI_FALSE;
    jboolean isCopyM = JNI_FALSE;
    const uint8_t* classes = reinterpret_cast<const uint8_t*>(env->GetByteArrayElements(jClasses, &isCopyC));
    uint8_t* outSeg = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(jOutSeg, &isCopyS));
    uint8_t* outMask = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(jOutMask, &isCopyM));

    // Build a 256-entry lookup table for walkable
    bool isWalkable[256];
    for (int i = 0; i < 256; ++i) isWalkable[i] = false;
    if (jWalkableIds) {
        const jsize wLen = env->GetArrayLength(jWalkableIds);
        jboolean isCopyW = JNI_FALSE;
        const jint* wIds = env->GetIntArrayElements(jWalkableIds, &isCopyW);
        for (int i = 0; i < wLen; ++i) {
            int v = wIds[i];
            if (v >= 0 && v < 256) isWalkable[v] = true;
        }
        env->ReleaseIntArrayElements(jWalkableIds, const_cast<jint*>(wIds), JNI_ABORT);
    }

    for (int fy = 0; fy < frameH; ++fy) {
        const int my = (int)(fy * scale + (float)padY);
        int myClamped = my; if (myClamped < 0) myClamped = 0; if (myClamped > modelH - 1) myClamped = modelH - 1;
        const int rowBase = myClamped * modelW;
        for (int fx = 0; fx < frameW; ++fx) {
            const int mx = (int)(fx * scale + (float)padX);
            int mxClamped = mx; if (mxClamped < 0) mxClamped = 0; if (mxClamped > modelW - 1) mxClamped = modelW - 1;
            const uint8_t cid = classes[rowBase + mxClamped];
            const int fIdx = fy * frameW + fx;
            outSeg[fIdx] = cid;
            outMask[fIdx] = isWalkable[cid] ? 1 : 0;
        }
    }

    env->ReleaseByteArrayElements(jClasses, reinterpret_cast<jbyte*>(const_cast<uint8_t*>(classes)), JNI_ABORT);
    env->ReleaseByteArrayElements(jOutSeg, reinterpret_cast<jbyte*>(outSeg), 0);
    env->ReleaseByteArrayElements(jOutMask, reinterpret_cast<jbyte*>(outMask), 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_frameRankDepth(
        JNIEnv* env, jclass,
        jfloatArray jRel,
        jint width,
        jint height,
        jfloatArray jPercentiles,
        jfloatArray jRankOut,
        jfloatArray jScratch) {
    if (!jRel || !jPercentiles || !jRankOut || !jScratch) return JNI_FALSE;
    const int total = width * height;
    if (env->GetArrayLength(jRankOut) < total || env->GetArrayLength(jPercentiles) < 3) {
        return JNI_FALSE;
    }

    jboolean isCopyScratch = JNI_FALSE;
    jboolean isCopyRel = JNI_FALSE;
    jboolean isCopyRanks = JNI_FALSE;
    jboolean isCopyPercent = JNI_FALSE;
    const float* rel = env->GetFloatArrayElements(jRel, &isCopyRel);
    float* ranks = env->GetFloatArrayElements(jRankOut, &isCopyRanks);
    float* percentiles = env->GetFloatArrayElements(jPercentiles, &isCopyPercent);
    float* scratch = env->GetFloatArrayElements(jScratch, &isCopyScratch);

    // Use scratch buffer instead of vector
    int count = 0;
    for (int i = 0; i < total; ++i) {
        const float v = rel[i];
        if (std::isfinite(v) && v > 0.f) {
            scratch[count++] = v;
        }
    }
    if (count == 0) {
        for (int i = 0; i < total; ++i) {
            ranks[i] = 0.5f;
        }
        percentiles[0] = std::numeric_limits<float>::quiet_NaN();
        percentiles[1] = std::numeric_limits<float>::quiet_NaN();
        percentiles[2] = 0.f;

        env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
        env->ReleaseFloatArrayElements(jRankOut, ranks, 0);
        env->ReleaseFloatArrayElements(jPercentiles, percentiles, 0);
        env->ReleaseFloatArrayElements(jScratch, scratch, JNI_ABORT);
        return JNI_TRUE;
    }

    const int p10Idx = std::max(0, std::min(count - 1, static_cast<int>(count * 0.10f)));
    const int p90Idx = std::max(0, std::min(count - 1, static_cast<int>(count * 0.90f)));

    std::nth_element(scratch, scratch + p10Idx, scratch + count);
    const float p10 = scratch[p10Idx];
    std::nth_element(scratch, scratch + p90Idx, scratch + count);
    const float p90 = scratch[p90Idx];

    const float denom = std::max(p90 - p10, 1e-5f);
    for (int i = 0; i < total; ++i) {
        const float v = rel[i];
        if (std::isfinite(v) && v > 0.f) {
            float rank = (v - p10) / denom;
            if (rank < 0.f) rank = 0.f;
            if (rank > 1.f) rank = 1.f;
            ranks[i] = rank;
        } else {
            ranks[i] = std::numeric_limits<float>::quiet_NaN();
        }
    }

    percentiles[0] = p10;
    percentiles[1] = p90;
    percentiles[2] = static_cast<float>(count);

    env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
    env->ReleaseFloatArrayElements(jRankOut, ranks, 0);
    env->ReleaseFloatArrayElements(jPercentiles, percentiles, 0);
    env->ReleaseFloatArrayElements(jScratch, scratch, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_frameRankDepthSampled(
        JNIEnv* env, jclass,
        jfloatArray jRel,
        jint width,
        jint height,
        jfloat sampleRatio,
        jfloatArray jPercentiles,
        jfloatArray jRankOut,
        jfloatArray jScratch) {
    if (!jRel || !jPercentiles || !jRankOut || !jScratch) return JNI_FALSE;
    const int total = width * height;
    if (env->GetArrayLength(jRankOut) < total || env->GetArrayLength(jPercentiles) < 3) {
        return JNI_FALSE;
    }

    jboolean isCopyScratch = JNI_FALSE;
    jboolean isCopyRel = JNI_FALSE;
    jboolean isCopyRanks = JNI_FALSE;
    jboolean isCopyPercent = JNI_FALSE;
    const float* rel = env->GetFloatArrayElements(jRel, &isCopyRel);
    float* ranks = env->GetFloatArrayElements(jRankOut, &isCopyRanks);
    float* percentiles = env->GetFloatArrayElements(jPercentiles, &isCopyPercent);
    float* scratch = env->GetFloatArrayElements(jScratch, &isCopyScratch);

    float ratio = sampleRatio;
    if (!std::isfinite(ratio)) ratio = 1.0f;
    if (ratio < 0.1f) ratio = 0.1f;
    if (ratio > 1.0f) ratio = 1.0f;
    int bandStart = (ratio >= 0.999f) ? 0 : static_cast<int>((1.0f - ratio) * height);
    if (bandStart < 0) bandStart = 0;
    if (bandStart > height) bandStart = height;

    int count = 0;
    for (int i = 0; i < total; ++i) {
        if (bandStart > 0) {
            const int y = i / width;
            if (y < bandStart) continue;
        }
        const float v = rel[i];
        if (std::isfinite(v) && v > 0.f) {
            scratch[count++] = v;
        }
    }

    if (count == 0) {
        for (int i = 0; i < total; ++i) {
            ranks[i] = 0.5f;
        }
        percentiles[0] = std::numeric_limits<float>::quiet_NaN();
        percentiles[1] = std::numeric_limits<float>::quiet_NaN();
        percentiles[2] = 0.f;

        env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
        env->ReleaseFloatArrayElements(jRankOut, ranks, 0);
        env->ReleaseFloatArrayElements(jPercentiles, percentiles, 0);
        env->ReleaseFloatArrayElements(jScratch, scratch, JNI_ABORT);
        return JNI_TRUE;
    }

    const int p10Idx = std::max(0, std::min(count - 1, static_cast<int>(count * 0.10f)));
    const int p90Idx = std::max(0, std::min(count - 1, static_cast<int>(count * 0.90f)));

    std::nth_element(scratch, scratch + p10Idx, scratch + count);
    const float p10 = scratch[p10Idx];
    std::nth_element(scratch, scratch + p90Idx, scratch + count);
    const float p90 = scratch[p90Idx];

    const float denom = std::max(p90 - p10, 1e-5f);
    for (int i = 0; i < total; ++i) {
        const float v = rel[i];
        if (std::isfinite(v) && v > 0.f) {
            float rank = (v - p10) / denom;
            if (rank < 0.f) rank = 0.f;
            if (rank > 1.f) rank = 1.f;
            ranks[i] = rank;
        } else {
            ranks[i] = std::numeric_limits<float>::quiet_NaN();
        }
    }

    percentiles[0] = p10;
    percentiles[1] = p90;
    percentiles[2] = static_cast<float>(count);

    env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
    env->ReleaseFloatArrayElements(jRankOut, ranks, 0);
    env->ReleaseFloatArrayElements(jPercentiles, percentiles, 0);
    env->ReleaseFloatArrayElements(jScratch, scratch, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_frameRankPercentilesSampled(
        JNIEnv* env, jclass,
        jfloatArray jRel,
        jint width,
        jint height,
        jfloat sampleRatio,
        jfloatArray jPercentiles,
        jfloatArray jScratch) {
    if (!jRel || !jPercentiles || !jScratch) return JNI_FALSE;
    const int total = width * height;
    if (env->GetArrayLength(jPercentiles) < 3) return JNI_FALSE;

    jboolean isCopyScratch = JNI_FALSE;
    jboolean isCopyRel = JNI_FALSE;
    jboolean isCopyPercent = JNI_FALSE;
    const float* rel = env->GetFloatArrayElements(jRel, &isCopyRel);
    float* percentiles = env->GetFloatArrayElements(jPercentiles, &isCopyPercent);
    float* scratch = env->GetFloatArrayElements(jScratch, &isCopyScratch);

    float ratio = sampleRatio;
    if (!std::isfinite(ratio)) ratio = 1.0f;
    if (ratio < 0.1f) ratio = 0.1f;
    if (ratio > 1.0f) ratio = 1.0f;
    int bandStart = (ratio >= 0.999f) ? 0 : static_cast<int>((1.0f - ratio) * height);
    if (bandStart < 0) bandStart = 0;
    if (bandStart > height) bandStart = height;

    int count = 0;
    for (int i = 0; i < total; ++i) {
        if (bandStart > 0) {
            const int y = i / width;
            if (y < bandStart) continue;
        }
        const float v = rel[i];
        if (std::isfinite(v) && v > 0.f) {
            scratch[count++] = v;
        }
    }

    if (count == 0) {
        percentiles[0] = std::numeric_limits<float>::quiet_NaN();
        percentiles[1] = std::numeric_limits<float>::quiet_NaN();
        percentiles[2] = 0.f;

        env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
        env->ReleaseFloatArrayElements(jPercentiles, percentiles, 0);
        env->ReleaseFloatArrayElements(jScratch, scratch, JNI_ABORT);
        return JNI_TRUE;
    }

    const int p10Idx = std::max(0, std::min(count - 1, static_cast<int>(count * 0.10f)));
    const int p90Idx = std::max(0, std::min(count - 1, static_cast<int>(count * 0.90f)));

    std::nth_element(scratch, scratch + p10Idx, scratch + count);
    const float p10 = scratch[p10Idx];
    std::nth_element(scratch, scratch + p90Idx, scratch + count);
    const float p90 = scratch[p90Idx];

    percentiles[0] = p10;
    percentiles[1] = p90;
    percentiles[2] = static_cast<float>(count);

    env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
    env->ReleaseFloatArrayElements(jPercentiles, percentiles, 0);
    env->ReleaseFloatArrayElements(jScratch, scratch, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_downsample2xMaxInto(
        JNIEnv* env, jclass,
        jfloatArray jSrc,
        jint width,
        jint height,
        jfloatArray jDst) {
    if (!jSrc || !jDst) return JNI_FALSE;
    const int outW = width / 2;
    const int outH = height / 2;
    if (env->GetArrayLength(jSrc) < width * height ||
        env->GetArrayLength(jDst) < outW * outH) {
        return JNI_FALSE;
    }

    jboolean isCopySrc = JNI_FALSE;
    jboolean isCopyDst = JNI_FALSE;
    const float* src = env->GetFloatArrayElements(jSrc, &isCopySrc);
    float* dst = env->GetFloatArrayElements(jDst, &isCopyDst);

    int outIdx = 0;
    for (int y = 0; y < outH; ++y) {
        const int srcY = y * 2;
        for (int x = 0; x < outW; ++x) {
            const int srcX = x * 2;
            float maxValue = -std::numeric_limits<float>::infinity();
            for (int dy = 0; dy <= 1; ++dy) {
                int sy = srcY + dy;
                if (sy < 0) sy = 0;
                if (sy > height - 1) sy = height - 1;
                const int rowBase = sy * width;
                for (int dx = 0; dx <= 1; ++dx) {
                    int sx = srcX + dx;
                    if (sx < 0) sx = 0;
                    if (sx > width - 1) sx = width - 1;
                    const float v = src[rowBase + sx];
                    if (std::isfinite(v) && v > maxValue) {
                        maxValue = v;
                    }
                }
            }
            dst[outIdx++] = maxValue;
        }
    }

    env->ReleaseFloatArrayElements(jSrc, const_cast<float*>(src), JNI_ABORT);
    env->ReleaseFloatArrayElements(jDst, dst, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_rankFromRawDepth(
        JNIEnv* env, jclass,
        jfloatArray jRel,
        jint width,
        jint height,
        jfloat p10,
        jfloat p90,
        jint sampleCount,
        jfloatArray jOut) {
    if (!jRel || !jOut) return JNI_FALSE;
    const int total = width * height;
    if (env->GetArrayLength(jRel) < total || env->GetArrayLength(jOut) < total) return JNI_FALSE;

    jboolean isCopyRel = JNI_FALSE;
    jboolean isCopyOut = JNI_FALSE;
    const float* rel = env->GetFloatArrayElements(jRel, &isCopyRel);
    float* out = env->GetFloatArrayElements(jOut, &isCopyOut);

    if (sampleCount <= 0 || !std::isfinite(p10) || !std::isfinite(p90)) {
        for (int i = 0; i < total; ++i) out[i] = 0.5f;
    } else {
        const float range = std::max(p90 - p10, 1e-5f);
        for (int i = 0; i < total; ++i) {
            const float v = rel[i];
            if (std::isfinite(v) && v > 0.f) {
                float rank = (v - p10) / range;
                if (rank < 0.f) rank = 0.f;
                if (rank > 1.f) rank = 1.f;
                out[i] = rank;
            } else {
                out[i] = std::numeric_limits<float>::quiet_NaN();
            }
        }
    }

    env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_workRank2xFromRawDepth(
        JNIEnv* env, jclass,
        jfloatArray jRel,
        jint width,
        jint height,
        jfloat p10,
        jfloat p90,
        jint sampleCount,
        jfloatArray jOut) {
    if (!jRel || !jOut) return JNI_FALSE;
    const int outW = width / 2;
    const int outH = height / 2;
    const int total = width * height;
    if (env->GetArrayLength(jRel) < total || env->GetArrayLength(jOut) < outW * outH) {
        return JNI_FALSE;
    }

    jboolean isCopyRel = JNI_FALSE;
    jboolean isCopyOut = JNI_FALSE;
    const float* rel = env->GetFloatArrayElements(jRel, &isCopyRel);
    float* out = env->GetFloatArrayElements(jOut, &isCopyOut);

    if (sampleCount <= 0 || !std::isfinite(p10) || !std::isfinite(p90)) {
        const int outTotal = outW * outH;
        for (int i = 0; i < outTotal; ++i) out[i] = 0.5f;
    } else {
        const float range = std::max(p90 - p10, 1e-5f);
        int outIdx = 0;
        for (int y = 0; y < outH; ++y) {
            const int srcY = y * 2;
            for (int x = 0; x < outW; ++x) {
                const int srcX = x * 2;
                float maxValue = -std::numeric_limits<float>::infinity();
                for (int dy = 0; dy <= 1; ++dy) {
                    int sy = srcY + dy;
                    if (sy < 0) sy = 0;
                    if (sy > height - 1) sy = height - 1;
                    const int rowBase = sy * width;
                    for (int dx = 0; dx <= 1; ++dx) {
                        int sx = srcX + dx;
                        if (sx < 0) sx = 0;
                        if (sx > width - 1) sx = width - 1;
                        const float v = rel[rowBase + sx];
                        if (std::isfinite(v) && v > maxValue) {
                            maxValue = v;
                        }
                    }
                }
                if (std::isfinite(maxValue) && maxValue > 0.f) {
                    float rank = (maxValue - p10) / range;
                    if (rank < 0.f) rank = 0.f;
                    if (rank > 1.f) rank = 1.f;
                    out[outIdx++] = rank;
                } else {
                    out[outIdx++] = std::numeric_limits<float>::quiet_NaN();
                }
            }
        }
    }

    env->ReleaseFloatArrayElements(jRel, const_cast<float*>(rel), JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_buildNearMaskFromRank(
        JNIEnv* env, jclass,
        jfloatArray jRank,
        jint width,
        jint height,
        jfloat threshold,
        jfloat ignoreTopRatio,
        jfloat ignoreBottomRatio,
        jfloat ignoreLeftRatio,
        jfloat ignoreRightRatio,
        jfloat maxNearShare,
        jbyteArray jOut) {
    if (!jRank || !jOut) return JNI_FALSE;
    const int total = width * height;
    if (env->GetArrayLength(jRank) < total || env->GetArrayLength(jOut) < total) {
        return JNI_FALSE;
    }

    jboolean isCopyRank = JNI_FALSE;
    jboolean isCopyOut = JNI_FALSE;
    const float* rank = env->GetFloatArrayElements(jRank, &isCopyRank);
    uint8_t* out = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(jOut, &isCopyOut));

    int ignoreTop = static_cast<int>(ignoreTopRatio * height);
    int ignoreBottom = static_cast<int>(ignoreBottomRatio * height);
    int ignoreLeft = static_cast<int>(ignoreLeftRatio * width);
    int ignoreRight = static_cast<int>(ignoreRightRatio * width);
    if (ignoreTop < 0) ignoreTop = 0;
    if (ignoreTop > height) ignoreTop = height;
    if (ignoreBottom < 0) ignoreBottom = 0;
    if (ignoreBottom > height) ignoreBottom = height;
    if (ignoreLeft < 0) ignoreLeft = 0;
    if (ignoreLeft > width) ignoreLeft = width;
    if (ignoreRight < 0) ignoreRight = 0;
    if (ignoreRight > width) ignoreRight = width;

    const int eligibleTop = ignoreTop;
    const int eligibleBottom = std::max(eligibleTop, height - ignoreBottom);
    const int eligibleLeft = ignoreLeft;
    const int eligibleRight = std::max(eligibleLeft, width - ignoreRight);

    int eligibleCount = 0;
    int nearCount = 0;

    for (int y = 0; y < height; ++y) {
        const bool inEligibleBandY = (y >= eligibleTop && y < eligibleBottom);
        const int rowOffset = y * width;
        for (int x = 0; x < width; ++x) {
            const int idx = rowOffset + x;
            const bool inEligibleBand = inEligibleBandY && (x >= eligibleLeft && x < eligibleRight);
            const float r = rank[idx];
            const bool isNear = inEligibleBand && std::isfinite(r) && r >= threshold;
            out[idx] = isNear ? 1 : 0;
            if (inEligibleBand) {
                ++eligibleCount;
                if (isNear) ++nearCount;
            }
        }
    }

    if (eligibleCount > 0) {
        const float nearShare = static_cast<float>(nearCount) / static_cast<float>(eligibleCount);
        if (nearShare > maxNearShare) {
            std::fill(out, out + total, 0);
        }
    }

    env->ReleaseFloatArrayElements(jRank, const_cast<float*>(rank), JNI_ABORT);
    env->ReleaseByteArrayElements(jOut, reinterpret_cast<jbyte*>(out), 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_upsampleRankNearest(
        JNIEnv* env, jclass,
        jfloatArray jRank,
        jint workW,
        jint workH,
        jint scale,
        jint frameW,
        jint frameH,
        jfloatArray jOut) {
    if (!jRank || !jOut) return JNI_FALSE;
    if (env->GetArrayLength(jRank) < workW * workH ||
        env->GetArrayLength(jOut) < frameW * frameH) {
        return JNI_FALSE;
    }

    jboolean isCopyRank = JNI_FALSE;
    jboolean isCopyOut = JNI_FALSE;
    const float* rank = env->GetFloatArrayElements(jRank, &isCopyRank);
    float* out = env->GetFloatArrayElements(jOut, &isCopyOut);

    const int maxY = workH - 1;
    const int maxX = workW - 1;
    const int s = (scale <= 0) ? 1 : scale;
    for (int y = 0; y < frameH; ++y) {
        int wy = y / s;
        if (wy < 0) wy = 0;
        if (wy > maxY) wy = maxY;
        const int rowBase = wy * workW;
        const int outRow = y * frameW;
        for (int x = 0; x < frameW; ++x) {
            int wx = x / s;
            if (wx < 0) wx = 0;
            if (wx > maxX) wx = maxX;
            out[outRow + x] = rank[rowBase + wx];
        }
    }

    env->ReleaseFloatArrayElements(jRank, const_cast<float*>(rank), JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_util_NativeImageOps_minMaxFinite(
        JNIEnv* env, jclass,
        jfloatArray jSrc,
        jfloatArray jOut) {
    if (!jSrc || !jOut) return JNI_FALSE;
    const int len = env->GetArrayLength(jSrc);
    if (env->GetArrayLength(jOut) < 3) return JNI_FALSE;

    jboolean isCopySrc = JNI_FALSE;
    jboolean isCopyOut = JNI_FALSE;
    const float* src = env->GetFloatArrayElements(jSrc, &isCopySrc);
    float* out = env->GetFloatArrayElements(jOut, &isCopyOut);

    float minVal = std::numeric_limits<float>::infinity();
    float maxVal = -std::numeric_limits<float>::infinity();
    int count = 0;
    for (int i = 0; i < len; ++i) {
        const float v = src[i];
        if (!std::isfinite(v)) continue;
        if (v < minVal) minVal = v;
        if (v > maxVal) maxVal = v;
        ++count;
    }

    if (count == 0) {
        out[0] = std::numeric_limits<float>::quiet_NaN();
        out[1] = std::numeric_limits<float>::quiet_NaN();
        out[2] = 0.f;
    } else {
        out[0] = minVal;
        out[1] = maxVal;
        out[2] = static_cast<float>(count);
    }

    env->ReleaseFloatArrayElements(jSrc, const_cast<float*>(src), JNI_ABORT);
    env->ReleaseFloatArrayElements(jOut, out, 0);
    return JNI_TRUE;
}
