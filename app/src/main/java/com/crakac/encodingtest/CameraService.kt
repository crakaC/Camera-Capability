package com.crakac.encodingtest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import com.crakac.encodingtest.util.AutoFitSurfaceView
import com.crakac.encodingtest.util.OrientationLiveData
import com.crakac.encodingtest.util.getPreviewOutputSize
import kotlinx.coroutines.*
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "CameraService"
private const val MIN_REQUIRED_RECORDING_TIME_MILLIS = 1000L

class CameraService(
    context: Context,
    private val cameraId: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    previewSurface: AutoFitSurfaceView
) : LifecycleObserver {
    private val contextRef = WeakReference(context.applicationContext)
    private val context: Context? get() = contextRef.get()
    private val cameraExecutor = Executors.newFixedThreadPool(1)
    private val previewRef = WeakReference(previewSurface)
    private val previewSurface: Surface? get() = previewRef.get()?.holder?.surface
    private var listener: StateListener? = null
    private val scope = CoroutineScope(Job())
    private val cameraManager = context.getSystemService<CameraManager>()!!
    private val contentResolver = context.contentResolver
    private val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    private lateinit var recorder: Recorder
    private lateinit var session: CameraCaptureSession
    private var camera: CameraDevice? = null
    private val orientationLiveData = OrientationLiveData(context, characteristics)
    private val orientationObserver = Observer<Int> { newOrientation ->
        orientation = newOrientation
    }

    private val previewRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface.holder.surface)
        }.build()
    }

    private val recordRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(recorder.getSurface())
            addTarget(previewSurface.holder.surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }.build()
    }

    private var recordingStartMillis = 0L
    private var outputUri: Uri? = null
    private var outputFd: ParcelFileDescriptor? = null
    private var orientation = 0
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> get() = _isRecording

    init {
        Log.d(TAG, "init()")
        previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "PreviewSurface created")
                val previewSize = getPreviewOutputSize(
                    previewSurface.display, characteristics, SurfaceHolder::class.java
                )
                previewSurface.setAspectRatio(previewSize.width, previewSize.height)
                initializeCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "PreviewSurface destroyed")
            }
        })
    }

    private fun initializeCamera() = scope.launch {
        camera = openCamera(cameraManager, cameraId, cameraExecutor)
        recorder = DefaultMediaRecorder(width, height, fps) { uri -> listener?.onSaved(uri) }
        session = createCaptureSession(camera!!, cameraExecutor)
        session.setRepeatingRequest(previewRequest, null, null)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        executor: Executor
    ): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "camera:${camera.id} opened")
                    cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "camera(${camera.id}) has been disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val msg = when (error) {
                        ERROR_CAMERA_DEVICE -> "Fatal (device)"
                        ERROR_CAMERA_DISABLED -> "Device policy"
                        ERROR_CAMERA_IN_USE -> "Camera in use"
                        ERROR_CAMERA_SERVICE -> "Fatal (service)"
                        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown"
                    }
                    val exc = RuntimeException("Camera ${camera.id} error: ($error) $msg")
                    Log.e(TAG, exc.message, exc)
                    if (cont.isActive) cont.resumeWithException(exc)
                }
            })
        }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        executor: Executor
    ): CameraCaptureSession =
        suspendCoroutine { cont ->
            val previewConfig = OutputConfiguration(previewSurface!!)
            val recordConfig = OutputConfiguration(recorder.getSurface())
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(previewConfig, recordConfig),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "CaptureSession configured")
                        cont.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val exc =
                            RuntimeException("Camera ${session.device.id} session configuration failed")
                        Log.e(TAG, exc.message, exc)
                        listener?.onCreateSessionFailed()
                        cont.resumeWithException(exc)
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        Log.d(TAG, "session closed")
                        close(camera)
                    }
                }
            )
            device.createCaptureSession(config)
        }

    private fun startRecording() {
        if (_isRecording.value == true) return
        _isRecording.value = true
        scope.launch {
            session.setRepeatingRequest(recordRequest, null, null)
            recorder.apply {
                prepare()
                setOrientationHint(orientation)
                start()
            }
            recordingStartMillis = System.currentTimeMillis()
        }
    }

    private fun stopRecording() {
        if (_isRecording.value == false) return
        _isRecording.value = false
        session.setRepeatingRequest(previewRequest, null, null)
        scope.launch {
            val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
            if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
            }
            val before = System.currentTimeMillis()
            recorder.stop()
            recorder.release()
            val ms = System.currentTimeMillis() - before
            Log.d(TAG, "recorder stop/release needs ${ms}ms")
        }
    }

    fun setStateListener(listener: StateListener) {
        this.listener = listener
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    private fun start() {
        Log.d(TAG, "start()")
        orientationLiveData.observeForever(orientationObserver)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    private fun stop() {
        Log.d(TAG, "stop()")
        orientationLiveData.removeObserver(orientationObserver)
        stopRecording()
        session.stopRepeating()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun release() {
        Log.d(TAG, "release()")
        listener = null
        scope.launch {
            close(camera)
            recorder.release()
            session.close()
            cameraExecutor.shutdown()
        }
        scope.cancel()
    }

    private fun close(camera: CameraDevice?) {
        try {
            camera?.close()
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
        }
    }

    fun toggleRecording() {
        if (isRecording.value == true) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    interface StateListener {
        fun onSaved(savedFileUri: Uri) {}
        fun onCreateSessionFailed() {}
    }
}