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

import android.Manifest
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

@Composable
fun NoPermission(permissionRequestLauncher: ActivityResultLauncher<Array<String>>) {
    Button(
        onClick = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@Button

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionRequestLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
            } else {
                permissionRequestLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
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
