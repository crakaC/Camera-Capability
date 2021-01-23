package com.crakac.encodingtest.recoder

import android.view.Surface

interface Recorder {
    fun getSurface(): Surface
    fun prepare(orientation: Int)
    fun start()
    fun stop()
    fun release()
}