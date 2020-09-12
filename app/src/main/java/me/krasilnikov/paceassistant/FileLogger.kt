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

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import timber.log.Timber
import java.io.File
import java.io.OutputStreamWriter

object FileLogger : Timber.Tree() {

    private val priorities = arrayOf("V", "V", "V", "D", "I", "W", "E", "A")
    private val writer: OutputStreamWriter
    private val pfd: ParcelFileDescriptor

    init {
        val f = File(PaceApplication.instance.cacheDir, "log")
        try {
            writer = f.outputStream().writer()
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            f.delete()
        }
    }

    val currentSize: Long
        get() = pfd.statSize

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val now = SystemClock.elapsedRealtime()/100
        writer.write(now.toString())
        writer.write(":")
        writer.write(priorities[priority.coerceIn(0, priorities.lastIndex)])
        writer.write(":")
        writer.write(tag ?: "NOTAG")
        writer.write(":")
        writer.write(message)
        writer.write("\n")
        writer.flush()
    }

    fun dupLogFile(): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(File("/proc/self/fd/${pfd.fd}"), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
