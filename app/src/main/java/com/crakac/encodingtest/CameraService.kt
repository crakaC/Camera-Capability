package com.crakac.encodingtest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import com.crakac.encodingtest.recoder.CodecRecorder
import com.crakac.encodingtest.recoder.Recorder
import com.crakac.encodingtest.util.AutoFitSurfaceView
import com.crakac.encodingtest.util.OrientationLiveData
import com.crakac.encodingtest.util.Util
import com.crakac.encodingtest.util.getPreviewOutputSize
import kotlinx.coroutines.*
import java.io.IOException
import java.lang.ref.WeakReference
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
    viewFinder: AutoFitSurfaceView
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(CoroutineName("CameraService"))

    private val previewRef = WeakReference(viewFinder)
    private val previewSurface: Surface get() = previewRef.get()!!.holder.surface

    private val cameraThread = HandlerThread("Camera Thread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var listener: StateListener? = null

    private val cameraManager = context.getSystemService<CameraManager>()!!
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
            addTarget(previewSurface)
        }.build()
    }

    private val recordRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recorder.getSurface())
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }.build()
    }

    private var recordingStartMillis = 0L
    private var orientation = 0
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> get() = _isRecording

    init {
        Log.d(TAG, "init()")
        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "PreviewSurface created")
                scope.launch {
                    initializeCamera()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                Log.d(TAG, "PreviewSurface changed")
                val previewSize = getPreviewOutputSize(
                    viewFinder.display, characteristics, SurfaceHolder::class.java
                )
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "PreviewSurface destroyed")
            }
        })
    }

    private suspend fun initializeCamera() {
        camera = openCamera(cameraManager, cameraId)
        if (!::recorder.isInitialized) {
            recorder = CodecRecorder(width, height, fps, 5_000_000, Util.getCodec()) { uri ->
                listener?.onSaved(uri)
            }
//          recorder = DefaultMediaRecorder(width, height, fps) { uri -> listener?.onSaved(uri) }
        }
        session = createCaptureSession(camera!!)
        session.setRepeatingRequest(previewRequest, null, null)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String
    ): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "camera:${camera.id} opened")
                    cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "camera(${camera.id}) has been disconnected")
                    camera.close()
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
                    camera.close()
                }
            }, cameraHandler)
        }

    private suspend fun createCaptureSession(
        device: CameraDevice
    ): CameraCaptureSession =
        suspendCoroutine { cont ->
            val t = System.currentTimeMillis()
            device.createCaptureSession(
                listOf(previewSurface, recorder.getSurface()),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(
                            TAG, "CaptureSession configured" +
                                    "(${System.currentTimeMillis() - t}ms)"
                        )
                        cont.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        val exc =
                            RuntimeException("Camera ${session.device.id} session configuration failed")
                        Log.e(TAG, exc.message, exc)
                        cont.resumeWithException(exc)
                    }
                },
                cameraHandler
            )
        }

    private fun startRecording() {
        if (_isRecording.value == true) return
        _isRecording.value = true
        session.setRepeatingRequest(
            recordRequest,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureSequenceCompleted(
                    session: CameraCaptureSession, sequenceId: Int, frameNumber: Long
                ) {
                    recorder.stop()
                }
            },
            cameraHandler
        )
        recorder.prepare(orientation)
        recorder.start()
        recordingStartMillis = System.currentTimeMillis()
    }

    private fun stopRecording() {
        if (_isRecording.value == false) return
        _isRecording.value = false
        scope.launch {
            val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
            if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
            }
            session.abortCaptures()
            session.setRepeatingRequest(previewRequest, null, null)
        }
    }

    fun setStateListener(listener: StateListener) {
        this.listener = listener
    }

    override fun onStart(owner: LifecycleOwner) {
        orientationLiveData.observeForever(orientationObserver)
    }

    override fun onStop(owner: LifecycleOwner) {
        orientationLiveData.removeObserver(orientationObserver)
        stopRecording()
        session.stopRepeating()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        listener = null
        close(camera)
        recorder.release()
        session.close()
        cameraThread.quitSafely()
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

    fun interface StateListener {
        fun onSaved(savedFileUri: Uri)
    }
}