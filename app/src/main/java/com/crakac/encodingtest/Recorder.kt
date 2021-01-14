package com.crakac.encodingtest

import android.view.Surface

interface Recorder {
    fun getSurface(): Surface
    fun setOrientationHint(orientation: Int)
    fun prepare()
    fun start()
    fun stop()
    fun release()
}