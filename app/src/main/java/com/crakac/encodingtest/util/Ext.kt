package com.crakac.encodingtest.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

fun ContentResolver.removePendingFile() {
    val uris = mutableListOf<Uri>()
    val projection = arrayOf(MediaStore.Video.Media._ID)
    query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection,
        "is_pending = ?",
        arrayOf("1"),
        null
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            uris.add(
                Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
            )
        }
    }
    for (uri in uris) {
        delete(uri, null, null)
        Log.d("ContentResolver", "removed $uri")
    }
}