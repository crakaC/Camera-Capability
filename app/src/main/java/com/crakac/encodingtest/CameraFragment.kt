package com.crakac.encodingtest

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.*
import android.widget.ImageView
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.crakac.encodingtest.databinding.FragmentCameraBinding
import com.crakac.encodingtest.util.AutoFitSurfaceView
import com.crakac.encodingtest.util.OrientationLiveData
import com.crakac.encodingtest.util.getPreviewOutputSize
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "CameraFragment"
private const val MIN_REQUIRED_RECORDING_TIME_MILLIS = 1000L

class CameraFragment : Fragment() {
    private val args: CameraFragmentArgs by navArgs()

    private val navController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }
    private val contentResolver by lazy {
        requireContext().contentResolver
    }
    private val cameraManager: CameraManager by lazy {
        requireContext().getSystemService()!!
    }

    private val characteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }
    private val recorderSurface by lazy {
        val surface = MediaCodec.createPersistentInputSurface()
        createRecorder(surface).apply {
            prepare()
            release()
        }
        surface
    }
    private var recorder: MediaRecorder? = null
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private lateinit var viewFinder: AutoFitSurfaceView
    private lateinit var subViewFinder: AutoFitSurfaceView
    private lateinit var captureButton: ImageView
    private lateinit var session: CameraCaptureSession
    private var camera: CameraDevice? = null
    private var subCamera: CameraDevice? = null
    private val previewRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(viewFinder.holder.surface)
        }.build()
    }
    private val recordRequest: CaptureRequest by lazy {
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(viewFinder.holder.surface)
            addTarget(recorderSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
        }.build()
    }

    private var recordingStartMillis = 0L
    private var isRecording = false
    private lateinit var relativeOrientation: OrientationLiveData

    private lateinit var outputUri: Uri
    private lateinit var outputFd: ParcelFileDescriptor

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentCameraBinding.inflate(inflater)
        viewFinder = binding.mainSurface
        subViewFinder = binding.subSurface
        captureButton = binding.captureButton
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val previewSize = getPreviewOutputSize(
                    viewFinder.display, characteristics, SurfaceHolder::class.java
                )
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)
                initializeCamera()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        captureButton.setOnClickListener {
            isRecording = !isRecording
            if (isRecording) {
                startRecording()
                captureButton.setImageResource(R.drawable.stop_recording)
            } else {
                stopRecording()
                captureButton.setImageResource(R.drawable.start_recording)
            }
        }
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner) { orientation ->
                Log.d(TAG, "orientation changed: $orientation")
            }
        }
    }

    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)
        val targets = listOf(viewFinder.holder.surface, recorderSurface)
        session = createCaptureSession(camera!!, targets, cameraHandler)
        session.setRepeatingRequest(previewRequest, null, cameraHandler)
    }

    private fun createRecorder(surface: Surface) = MediaRecorder().apply {

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/${getString(R.string.app_name)}"
            )
            put(MediaStore.Video.Media.DISPLAY_NAME, generateFilename("mp4"))
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        outputUri = contentResolver.insert(collection, values)!!
        outputFd = contentResolver.openFileDescriptor(outputUri, "rw")!!

        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)

        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFd.fileDescriptor)

        setAudioChannels(1)
        setAudioEncodingBitRate(64 * 1024)
        setAudioSamplingRate(44100)
        if (args.fps > 0) setVideoFrameRate(args.fps)
        setVideoSize(args.width, args.height)
        setVideoEncodingBitRate(5 * 1024 * 1024)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
        setInputSurface(surface)
    }

    private fun startRecording() = lifecycleScope.launch(Dispatchers.Default) {
        recorder = createRecorder(recorderSurface)
        // Prevents screen rotation during the video recording
        requireActivity().requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LOCKED

        session.setRepeatingRequest(recordRequest, null, cameraHandler)
        recorder?.apply {
            relativeOrientation.value?.let { setOrientationHint(it) }
            prepare()
            start()
        }
        recordingStartMillis = System.currentTimeMillis()
    }

    private fun stopRecording() = lifecycleScope.launch(Dispatchers.Default) {
        val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
        if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
            delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
        }
        recorder?.stop()
        recorder?.release()
        recorder = null
        requireActivity().requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        try {
            outputFd.close()
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
        }

        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        val rows = contentResolver.update(outputUri, values, null, null)
        Log.i(TAG, "$rows rows updated")

        Snackbar.make(viewFinder, "Saved", Snackbar.LENGTH_LONG).setAction("Open") {
            startActivity(Intent().apply {
                action = Intent.ACTION_VIEW
                type = "video/mp4"
                data = outputUri
//                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }.show()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) = cont.resume(camera)

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "$cameraId has been disconnected")
                requireActivity().finish()
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
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    override fun onStop() {
        super.onStop()
        for (camera in arrayOf(camera, subCamera)) {
            try {
                camera?.close()
            } catch (e: Throwable) {
                Log.e(TAG, "Error closing camera", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        recorder?.release()
        recorderSurface.release()
    }

    private fun generateFilename(ext: String) =
        "${
            SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.getDefault()
            ).format(Date())
        }.$ext"
}