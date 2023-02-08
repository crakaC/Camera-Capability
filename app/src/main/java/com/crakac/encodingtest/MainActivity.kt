package com.crakac.encodingtest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

private val Permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
private const val REQUEST_ID = 111

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ActivityCompat.requestPermissions(this, Permissions, REQUEST_ID)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!checkPermissions()) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun checkPermissions() = Permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}