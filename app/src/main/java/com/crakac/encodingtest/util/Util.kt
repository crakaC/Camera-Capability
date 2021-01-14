package com.crakac.encodingtest.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.crakac.encodingtest.R
import java.text.SimpleDateFormat
import java.util.*

class Util private constructor() {
    companion object {
        private lateinit var context: Context
        private val contentResolver by lazy { context.contentResolver }
        fun init(application: Application) {
            context = application
        }

        fun createNewFileUri(): Uri {
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
        }

        fun openFileDescriptor(uri: Uri) = contentResolver.openFileDescriptor(uri, "rw")!!

        private fun generateFilename(ext: String) =
            "${
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss_SSS",
                    Locale.getDefault()
                ).format(Date())
            }.$ext"
    }
}