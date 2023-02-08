package com.crakac.encodingtest

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Size
import android.view.*
import android.widget.TextView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crakac.encodingtest.util.GenericListAdapter

class SelectorFragment : Fragment() {
    private val navController: NavController by lazy {
        Navigation.findNavController(
            requireActivity(),
            R.id.fragment_container
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = RecyclerView(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view as RecyclerView
        view.apply {
            layoutManager = LinearLayoutManager(requireContext())
            val cameraManager =
                requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraList = enumerateVideoCameras(cameraManager)
            adapter = GenericListAdapter(
                cameraList,
                android.R.layout.simple_list_item_1
            ) { view, item, _ ->
                view.findViewById<TextView>(android.R.id.text1).text = item.name
                view.setOnClickListener {
                    navController.navigate(
                        SelectorFragmentDirections.actionSelectorFragmentToCameraFragment(
                            item.cameraId, item.size.width, item.size.height, item.fps
                        )
                    )
                }

            }
        }
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_selectorfragment, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.select_codec) {
                    navController.navigate(SelectorFragmentDirections.actionSelectorFragmentToCodecSelectFragment())
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    companion object {
        private data class CameraInfo(
            val name: String,
            val cameraId: String,
            val size: Size,
            val fps: Int
        )

        private fun lensOrientationString(value: Int) = when (value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        private fun enumerateVideoCameras(cameraManager: CameraManager): List<CameraInfo> {
            val availableCameras = mutableListOf<CameraInfo>()
            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = lensOrientationString(
                    characteristics.get(CameraCharacteristics.LENS_FACING)!!
                )
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                )!!
                val cameraConfig = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )!!

                if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    val targetClass = MediaRecorder::class.java
                    cameraConfig.getOutputSizes(targetClass).forEach { size ->
                        val secondsPerFrame = cameraConfig.getOutputMinFrameDuration(
                            targetClass,
                            size
                        ) / 1_000_000_000.0
                        val fps = if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 0
                        val fpsLabel = if (fps > 0) "$fps" else "N/A"
                        availableCameras.add(
                            CameraInfo(
                                "$orientation, $id, $size $fpsLabel FPS", id, size, fps
                            )
                        )
                    }
                }
            }
            return availableCameras
        }
    }
}