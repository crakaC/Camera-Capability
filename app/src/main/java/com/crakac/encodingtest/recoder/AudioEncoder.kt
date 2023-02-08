package com.crakac.encodingtest.recoder

import android.annotation.SuppressLint
import android.media.*
import android.util.Log
import com.crakac.encodingtest.util.LOG
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class AudioEncoder(
    private val samplingRate: Int = 44100,
    bitrate: Int = 64 * 1024,
    muxer: Muxer
) : Encoder {
    companion object {
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val CHANNEL_COUNT = 1
        private const val TAG: String = "AudioEncoder"
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NANOS_PER_MICROS = 1_000L
    }

    private val scope = CoroutineScope(
        Executors.newFixedThreadPool(2).asCoroutineDispatcher() +
                CoroutineName("AudioEncoder")
    )
    private var recordingJob: Job? = null

    private val muxerRef = WeakReference(muxer)
    private val muxer: Muxer get() = muxerRef.get()!!

    private var isEncoding = false

    private val audioBufferSize = AudioRecord.getMinBufferSize(
        samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val audioBuffer = ByteArray(audioBufferSize)
    private val bytesPerFrame = 2 * CHANNEL_COUNT
    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.CAMCORDER,
        samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        audioBufferSize
    )

    private val format =
        MediaFormat.createAudioFormat(MIME_TYPE, samplingRate, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioBufferSize)
        }
    private val codec = MediaCodec.createEncoderByType(MIME_TYPE)

    private var prevTimestamp = 0L
    private fun getTimestamp(): Long {
        prevTimestamp = maxOf(System.nanoTime() / 1000, prevTimestamp + 1)
        return prevTimestamp
    }

    fun prepare() {
        codec.reset()
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    override fun start() {
        audioRecord.startRecording()
        codec.start()
        isEncoding = true
        recordingJob = record()
        drain()
    }

    private fun record() = scope.launch {
        var totalNumFramesRead = 0L
        while (isActive) {
            val bytes = audioRecord.read(audioBuffer, 0, audioBufferSize)
            if (bytes < 0) {
                val msg = when (bytes) {
                    AudioRecord.ERROR_INVALID_OPERATION -> "Invalid Operation"
                    AudioRecord.ERROR_BAD_VALUE -> "Bad Value"
                    AudioRecord.ERROR_DEAD_OBJECT -> "Dead Object"
                    else -> "Unknown Error"
                }
                Log.e(TAG, msg)
                continue
            }
            val inputBufferId = codec.dequeueInputBuffer(-1)
            val inputBuffer = codec.getInputBuffer(inputBufferId)
            inputBuffer!!.put(audioBuffer)
            codec.queueInputBuffer(inputBufferId, 0, bytes, getTimestamp(totalNumFramesRead), 0)
            totalNumFramesRead += bytes / bytesPerFrame
        }
    }

    // https://github.com/google/mediapipe/blob/master/mediapipe/java/com/google/mediapipe/components/MicrophoneHelper.java
    private val audioTimestamp = AudioTimestamp()
    private fun getTimestamp(framePosition: Long): Long {
        audioRecord.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
        val referenceFrame = audioTimestamp.framePosition
        val referenceTimestamp = audioTimestamp.nanoTime
        val timestampNanos =
            referenceTimestamp + (framePosition - referenceFrame) * NANOS_PER_SECOND / samplingRate
        return timestampNanos / NANOS_PER_MICROS
    }

    private fun drain() = scope.launch {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackId = -1
        while (true) {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, -1)
            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                LOG(TAG, "OUTPUT_FORMAT_CHANGED")
                trackId = muxer.addTrack(codec.outputFormat)
            } else {
                val encodedData = codec.getOutputBuffer(outputBufferId)
                    ?: throw RuntimeException("encodedData is null")
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeData(trackId, encodedData, bufferInfo)
                }
                codec.releaseOutputBuffer(outputBufferId, false)
            }
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                LOG(TAG, "EOS")
                break
            }
        }
        codec.stop()
        LOG(TAG, "AudioEncoder FINISHED")
        muxer.stopMuxer()
        isEncoding = false
    }

    override fun stop() {
        audioRecord.stop()
        recordingJob?.cancel()
        val id = codec.dequeueInputBuffer(-1)
        codec.queueInputBuffer(id, 0, 0, getTimestamp(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    override fun isEncoding() = isEncoding

    fun release() {
        audioRecord.release()
        codec.release()
        scope.cancel()
    }
}