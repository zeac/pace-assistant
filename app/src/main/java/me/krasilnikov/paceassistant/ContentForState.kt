/*
 * Copyright 2024 Alexey Krasilnikov
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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun ContentForState(
    state: State,
    startTime: Long = 0L,
    assisting: Boolean = false,
) {
    ContentForState(
        permissionRequestLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) {

        },

        state = state,
        startTime = startTime,
        assisting = assisting,

        onStopClicked = {},
        onAssistingChanged = {},
    )
}

@Composable
fun ContentForState(
    permissionRequestLauncher: ActivityResultLauncher<Array<String>>,
    state: State,
    startTime: Long = 0L,
    assisting: Boolean,
    onStopClicked: () -> Unit,
    onAssistingChanged: (assisting: Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            when (state) {
                State.NoPermission -> NoPermissionDescription()
                else -> Unit
            }
        }

        Box(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                State.NoBluetooth -> NoBluetooth()
                State.BluetoothIsTurnedOff -> BluetoothIsTurnedOff()
                State.NoPermission -> NoPermission(permissionRequestLauncher)
                State.Scanning -> Scanning()
                is State.Monitor -> ShowHeartbeat(state)
                is State.Assist -> ShowHeartbeat(state)
            }
        }

        Box(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (state is State.Monitor || state is State.Assist) {
                if (state is State.Assist && startTime > state.assistStartTime) {
                    StopButton(
                        onClick = onStopClicked,
                    )
                } else {
                    AssistantControl(
                        assisting = assisting,
                        onAssistingChanged = onAssistingChanged,
                    )
                }
            }
        }
    }
}

@Composable
@Preview(showSystemUi = true, device = Devices.PIXEL_3)
private fun ShowHeartbeatPreview() {
    ContentForState(state = State.Assist(beat = 120, deviceName = "Polar H7", assistStartTime = 0L))
}

@Composable
@Preview(showSystemUi = true, device = Devices.PIXEL_3)
private fun ScanningPreview() {
    ContentForState(state = State.Scanning)
}

@Composable
@Preview(showSystemUi = true, device = Devices.PIXEL_3)
private fun NoPermissionPreview() {
    ContentForState(state = State.NoPermission)
}

@Composable
@Preview(showSystemUi = true, device = Devices.PIXEL_3)
private fun NoBluetoothPreview() {
    ContentForState(state = State.NoBluetooth)
}
