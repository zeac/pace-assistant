package me.krasilnikov.paceassistant

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Box
import androidx.compose.foundation.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.unit.sp
import androidx.ui.tooling.preview.Devices
import androidx.ui.tooling.preview.Preview

class MainActivity : AppCompatActivity() {

    private var subscription: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PaceTheme {
                val state by Worker.state.observeAsState()

                when (val s = state) {
                    State.NoBluetooth -> noBluetooth()
                    State.NoPermission -> noPermission()
                    is State.Heartbeat -> showHeartbeat(s.beat)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

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
    @Preview(showDecoration = true, device = Devices.PIXEL_3)
    private fun showHeartbeat(beat: Int = 120) {
        Box(
            gravity = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                fontSize = 96.sp,
                text = "$beat",
                color = MaterialTheme.colors.onSurface,
            )
        }
    }

    @Composable
    @Preview(showDecoration = true, device = Devices.PIXEL_3)
    private fun noPermission() {
        Box(
            gravity = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@Button

                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ), 0
                    )
                }
            ) {
                Text(
                    text = "Give permission",
                    color = MaterialTheme.colors.onPrimary,
                )
            }
        }
    }

    @Composable
    @Preview(showDecoration = true, device = Devices.PIXEL_3)
    private fun noBluetooth() {
        Box(
            gravity = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = "No bluetooth",
                color = MaterialTheme.colors.onSurface,
            )
        }
    }
}
