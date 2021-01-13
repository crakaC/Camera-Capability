package com.crakac.encodingtest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.crakac.encodingtest.databinding.FragmentCameraBinding
import com.crakac.encodingtest.util.AutoFitSurfaceView
import com.crakac.encodingtest.util.getPreviewOutputSize
import com.google.android.material.snackbar.Snackbar

private const val TAG = "CameraFragment"

class CameraFragment : Fragment() {
    private val args: CameraFragmentArgs by navArgs()
    private val viewModel: CameraViewModel by viewModels {
        CameraViewModel.Factory(requireActivity().application, args.cameraId)
    }

    private lateinit var cameraService: CameraService
    private lateinit var viewFinder: AutoFitSurfaceView
    private lateinit var captureButton: ImageView
    private var relativeOrientation = 0

    private val listener = object : CameraService.StateListener {
        override fun onSaved(savedFileUri: Uri) {
            Snackbar.make(viewFinder, "Saved", Snackbar.LENGTH_LONG).setAction("Open") {
                startActivity(Intent().apply {
                    action = Intent.ACTION_VIEW
                    type = "video/mp4"
                    data = savedFileUri
                })
            }.show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentCameraBinding.inflate(inflater)
        viewFinder = binding.mainSurface
        captureButton = binding.captureButton
        cameraService = CameraService(
            requireContext(),
            args.cameraId,
            args.width,
            args.height,
            args.fps,
            viewFinder
        )
        cameraService.setStateListener(listener)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val previewSize = getPreviewOutputSize(
                    viewFinder.display, cameraService.characteristics, SurfaceHolder::class.java
                )
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)
                cameraService.init()
            }

            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        captureButton.setOnClickListener {
            viewModel.onClickCapture()
        }
        viewModel.relativeOrientation.observe(viewLifecycleOwner) { orientation ->
            relativeOrientation = orientation
        }
        viewModel.isRecording.observe(viewLifecycleOwner) { isRecording ->
            if (isRecording) {
                cameraService.startRecording(relativeOrientation)
                captureButton.setImageResource(R.drawable.stop_recording)
            } else {
                cameraService.stopRecording()
                captureButton.setImageResource(R.drawable.start_recording)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        cameraService.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraService.release()
    }
}