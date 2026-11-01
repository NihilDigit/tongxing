package com.nihildigit.qnninferencesdk.sdk

import com.nihildigit.qnninferencesdk.core.TFLiteHelper

data class SdkConfig(
    // 默认采用自适应策略：QNN -> GPU -> CPU，避免在不支持 QNN 的设备上崩溃
    val backendMode: BackendMode = BackendMode.DEFAULT
)

enum class BackendMode { DEFAULT, QNN_ONLY, GPU_ONLY, CPU_ONLY }

internal fun BackendMode.applyToHelper() {
    TFLiteHelper.backendMode = when (this) {
        BackendMode.DEFAULT -> TFLiteHelper.BackendMode.DEFAULT
        BackendMode.QNN_ONLY -> TFLiteHelper.BackendMode.QNN_ONLY
        BackendMode.GPU_ONLY -> TFLiteHelper.BackendMode.GPU_ONLY
        BackendMode.CPU_ONLY -> TFLiteHelper.BackendMode.CPU_ONLY
    }
}
