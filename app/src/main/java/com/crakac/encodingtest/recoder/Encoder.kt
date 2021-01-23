package com.crakac.encodingtest.recoder

interface Encoder {
    fun start()
    fun stop()
    fun isEncoding(): Boolean
}