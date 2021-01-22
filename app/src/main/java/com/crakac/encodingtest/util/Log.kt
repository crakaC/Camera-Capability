package com.crakac.encodingtest.util

import android.util.Log
import com.crakac.encodingtest.BuildConfig

fun LOG(tag: String, msg: String) {
    if (!BuildConfig.DEBUG) return
    val ast = "*".repeat(maxOf((50 - msg.length) / 2, 1))
    Log.d(tag, "${ast}${msg}${ast}")
}