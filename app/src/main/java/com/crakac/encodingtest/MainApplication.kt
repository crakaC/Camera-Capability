package com.crakac.encodingtest

import android.app.Application
import com.crakac.encodingtest.util.Util

@Suppress("unused")
class MainApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        Util.init(this)
    }
}