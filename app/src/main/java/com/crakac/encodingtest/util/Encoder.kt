package com.crakac.encodingtest.util

interface Encoder {
    fun start()
    fun stop()
    fun isEncoding(): Boolean
}