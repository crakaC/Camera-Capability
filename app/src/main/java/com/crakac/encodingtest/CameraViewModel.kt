package com.crakac.encodingtest

import android.app.Application
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import com.crakac.encodingtest.util.OrientationLiveData

class CameraViewModel(application: Application, cameraId: String) : AndroidViewModel(application) {
    private val TAG: String = "CameraViewModel"
    private val cameraManager = application.getSystemService<CameraManager>()!!
    private val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> get() = _isRecording
    val relativeOrientation = OrientationLiveData(application, characteristics)

    fun onClickCapture() {
        _isRecording.value = !isRecording.value!!
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "CameraViewModel cleard()")
    }

    class Factory(
        private val application: Application,
        private val cameraId: String
    ) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return CameraViewModel(application, cameraId) as T
        }
    }
}