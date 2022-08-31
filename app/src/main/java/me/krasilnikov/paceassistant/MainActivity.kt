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

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : AppCompatActivity() {

    private val startTime = mutableStateOf(0L)
    private var subscription: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = getString(R.string.app_name)

        setContent {
            PaceTheme {
                val state by Worker.state.collectAsState()

                contentForState(state)
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

        startTime.value = SystemClock.elapsedRealtime()
        subscription = Worker.subscribe(this)
    }

    override fun onStop() {
        super.onStop()

        subscription?.close()
        subscription = null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Worker.updatePermission()
    }

    @Composable
    private fun contentForState(state: State) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(1.0f).fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                when (state) {
                    State.NoPermission -> noPermissionDescription()
                    else -> Unit
                }
            }

            Box(
                modifier = Modifier.weight(1.0f).fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    State.NoBluetooth -> noBluetooth()
                    State.BluetoothIsTurnedOff -> bluetoothIsTurnedOff()
                    State.NoPermission -> noPermission()
                    State.Scanning -> scanning()
                    is State.Monitor -> showHeartbeat(state)
                    is State.Assist -> showHeartbeat(state)
                }
            }

            Box(
                modifier = Modifier.weight(1.0f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (state is State.Monitor || state is State.Assist) {
                    if (state is State.Assist && startTime.value > state.assistStartTime) {
                        stopButton()
                    } else {
                        assistantControl()
                    }
                }
            }
        }
    }

    @Composable
    private fun stopButton() {
        Button(onClick = { Worker.stop(true) }) {
            Text(text = stringResource(R.string.stop_assist))
        }
    }

    @Composable
    private fun assistantControl() {
        val assisting by Worker.assisting.observeAsState(Worker.assisting.value!!)

        Row(
            modifier = Modifier.toggleable(value = assisting, onValueChange = {
                Worker.assisting.value = it
            })
        ) {

            Text(
                text = stringResource(R.string.voice_assist),
                color = MaterialTheme.colors.onSurface,
            )

            Switch(checked = assisting, onCheckedChange = {
                Worker.assisting.value = it
            })
        }
    }

    @Composable
    private fun showHeartbeat(state: HeartbeatState) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                fontSize = 96.sp,
                text = "${state.beat / 10}.${state.beat % 10}",
                color = MaterialTheme.colors.onSurface,
            )
            Text(
                text = stringResource(R.string.beats_per_6_seconds),
                color = MaterialTheme.colors.onSurface,
            )
            Text(
                text = state.deviceName,
                color = MaterialTheme.colors.onSurface,
            )
        }
    }

    @Composable
    private fun scanning() {
        Text(
            color = MaterialTheme.colors.onSurface,
            text = stringResource(R.string.connecting),
            textAlign = TextAlign.Center,
        )
    }

    @Composable
    private fun noPermissionDescription() {
        Text(
            color = MaterialTheme.colors.onSurface,
            text = stringResource(R.string.give_permission_desc),
            textAlign = TextAlign.Center,
        )
    }

    @Composable
    private fun noPermission() {
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@Button

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ), 0
                    )
                } else {
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ), 0
                    )
                }
            }
        ) {
            Text(
                color = MaterialTheme.colors.onPrimary,
                text = stringResource(R.string.give_permission),
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun noBluetooth() {
        Text(
            color = MaterialTheme.colors.onSurface,
            text = stringResource(R.string.no_bluetooth),
            textAlign = TextAlign.Center,
        )
    }

    @Composable
    @SuppressLint("MissingPermission")
    private fun bluetoothIsTurnedOff() {
        Button(onClick = {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }) {
            Text(
                color = MaterialTheme.colors.onPrimary,
                text = stringResource(R.string.enable_bluetooth),
            )
        }
    }

    @Composable
    @Preview(showSystemUi = true, device = Devices.PIXEL_3)
    private fun showHeartbeatPreview() {
        contentForState(state = State.Assist(beat = 120, deviceName = "Polar H7", assistStartTime = 0L))
    }

    @Composable
    @Preview(showSystemUi = true, device = Devices.PIXEL_3)
    private fun scanningPreview() {
        contentForState(state = State.Scanning)
    }

    @Composable
    @Preview(showSystemUi = true, device = Devices.PIXEL_3)
    private fun noPermissionPreview() {
        contentForState(state = State.NoPermission)
    }

    @Composable
    @Preview(showSystemUi = true, device = Devices.PIXEL_3)
    private fun noBluetoothPreview() {
        contentForState(state = State.NoBluetooth)
    }
}
