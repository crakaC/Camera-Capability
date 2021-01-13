package com.crakac.encodingtest

import android.app.Application
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import com.crakac.encodingtest.util.OrientationLiveData

class CameraViewModel(
    application: Application,
    private val cameraId: String,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val targetFps: Int
) : AndroidViewModel(application) {
    private val TAG: String = "CameraViewModel"

    private val cameraManager: CameraManager = application.getSystemService()!!
    private val contentResolver = application.contentResolver
    private val characteristics = cameraManager.getCameraCharacteristics(cameraId)

    private var recorder: MediaRecorder? = null
    private var cameraThread = HandlerThread("CameraThread").apply { start() }
    private var cameraHandler = Handler(cameraThread.looper)
    private lateinit var session: CameraCaptureSession

    private var camera: CameraDevice? = null

    private var recordingStartMillis = 0L
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> get() = _isRecording
    val relativeOrientation = OrientationLiveData(application, characteristics)


    init {

    }


    override fun onCleared() {
        super.onCleared()

    }


    class Factory(
        private val application: Application,
        private val cameraId: String,
        private val width: Int,
        private val height: Int,
        private val fps: Int
    ) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return CameraViewModel(application, cameraId, width, height, fps) as T
        }
    }
}

