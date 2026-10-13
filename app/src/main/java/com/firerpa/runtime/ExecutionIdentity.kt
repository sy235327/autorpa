package com.firerpa.runtime

import androidx.annotation.StringRes
import com.firerpa.R

enum class ExecutionIdentity(@StringRes val labelResId: Int) {
    ROOT(R.string.identity_root),
    SHIZUKU_ROOT(R.string.identity_shizuku_root),
    SHIZUKU_SHELL(R.string.identity_shizuku),
    NONE(R.string.identity_unavailable),
}
