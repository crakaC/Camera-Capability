package com.crakac.encodingtest

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.crakac.encodingtest.databinding.FragmentCameraBinding
import com.crakac.encodingtest.util.AutoFitSurfaceView
import com.google.android.material.snackbar.Snackbar

private const val TAG = "CameraFragment"

class CameraFragment : Fragment() {
    private val args: CameraFragmentArgs by navArgs()

    private lateinit var cameraService: CameraService
    private lateinit var viewFinder: AutoFitSurfaceView
    private lateinit var captureButton: ImageView
    private val listener = CameraService.StateListener { savedFileUri ->
        Snackbar.make(viewFinder, "Saved", Snackbar.LENGTH_LONG).setAction("Open") {
            context?.startActivity(Intent().apply {
                action = Intent.ACTION_VIEW
                type = "video/mp4"
                data = savedFileUri
                flags =
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            })
        }.show()
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
        lifecycle.addObserver(cameraService)
        cameraService.isRecording.observe(viewLifecycleOwner) { isRecording ->
            if (isRecording) {
                captureButton.setImageResource(R.drawable.stop_recording)
            } else {
                captureButton.setImageResource(R.drawable.start_recording)
            }
        }
        captureButton.setOnClickListener {
            cameraService.toggleRecording()
        }
        return binding.root
    }
}