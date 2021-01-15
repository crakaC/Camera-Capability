package com.crakac.encodingtest.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.crakac.encodingtest.BuildConfig
import com.crakac.encodingtest.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class Util private constructor() {
    companion object {
        private const val PREFS_NAME = "info.pref"
        private const val CODEC = "codec"
        private lateinit var context: Context
        private val contentResolver by lazy { context.contentResolver }
        fun init(application: Application) {
            context = application
        }

        fun createNewFileUri(): Uri {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/${context.getString(R.string.app_name)}"
                    )
                    put(MediaStore.Video.Media.DISPLAY_NAME, generateFilename("mp4"))
//                put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                return context.contentResolver.insert(collection, values)!!
            } else {
                val outputFile = File(context.getExternalFilesDir(null), generateFilename("mp4"))
                return FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.provider",
                    outputFile
                )
            }
        }

        fun getFileDescriptor(uri: Uri) =
            contentResolver.openFileDescriptor(uri, "rw")?.fileDescriptor

        fun getCodec() = sharedPrefs.getString(CODEC, null)
        fun saveCodec(codec: String) {
            sharedPrefs.edit {
                putString(CODEC, codec)
            }
        }

        private val sharedPrefs: SharedPreferences
            get() = context.getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )

        private fun generateFilename(ext: String) =
            "${
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss_SSS",
                    Locale.getDefault()
                ).format(Date())
            }.$ext"
    }
}