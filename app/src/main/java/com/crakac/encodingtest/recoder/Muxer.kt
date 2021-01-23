package com.crakac.encodingtest.recoder

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

interface Muxer{
    /**
     * Block until track count is reached to expected counts
     */
    fun addTrack(format: MediaFormat): Int
    fun writeData(trackId: Int, data: ByteBuffer, info: MediaCodec.BufferInfo)
    fun stopMuxer()
}