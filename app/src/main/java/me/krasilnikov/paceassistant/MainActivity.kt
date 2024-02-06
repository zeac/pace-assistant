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

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableLongStateOf

class MainActivity : AppCompatActivity(), ActivityResultCallback<Map<String, Boolean>> {

    private val permissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        this,
    )

    private val startTime = mutableLongStateOf(0L)
    private var subscription: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = getString(R.string.app_name)

        setContent {
            PaceTheme {
                val state by Worker.state.collectAsState()
                val assisting by Worker.assisting.observeAsState(Worker.assisting.value!!)

                ContentForState(
                    permissionRequestLauncher = permissionRequestLauncher,
                    state = state,
                    startTime = startTime.longValue,
                    assisting = assisting,
                    onStopClicked = { Worker.stop(true) },
                    onAssistingChanged = { Worker.assisting.value = it },
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.share_log) {
            val uri = Uri.parse("content://me.krasilnikov.paceassistant.log/log.txt")
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf("paceassistant@krasilnikov.me"))
                putExtra(Intent.EXTRA_SUBJECT, "Pace Assistant log report")

                clipData = ClipData("Log file", arrayOf("text/plain"), ClipData.Item(uri))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            startActivity(Intent.createChooser(intent, "Share log"))
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()

        startTime.longValue = SystemClock.elapsedRealtime()
        subscription = Worker.subscribe(this)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onActivityResult(result: Map<String, Boolean>) {
        Worker.updatePermission()
    }

    override fun onStop() {
        super.onStop()

        subscription?.close()
        subscription = null
    }
}
