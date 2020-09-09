/*
 * Copyright 2020 Alexey Krasilnikov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.krasilnikov.paceassistant

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

class LogProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val cols = mutableListOf<String>()
        val values = mutableListOf<Any?>()

        if (projection == null) {
            cols.add(OpenableColumns.DISPLAY_NAME)
            values.add("log")

            cols.add(OpenableColumns.SIZE)
            values.add(FileLogger.currentSize)

        } else for (col in projection) {

            if (col == OpenableColumns.DISPLAY_NAME) {
                cols.add(OpenableColumns.DISPLAY_NAME)
                values.add("log")

            } else if (col == OpenableColumns.SIZE) {
                cols.add(OpenableColumns.SIZE)
                values.add(10)
            }
        }

        return MatrixCursor(cols.toTypedArray(), 1).apply {
            addRow(values)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw UnsupportedOperationException()

        return FileLogger.dupLogFile()
    }

    override fun getType(uri: Uri): String? {
        return "text/plain"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException()
    }
}
