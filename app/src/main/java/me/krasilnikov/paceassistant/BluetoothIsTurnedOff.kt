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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
@SuppressLint("MissingPermission")
fun BluetoothIsTurnedOff() {
    val context = LocalContext.current

    Button(onClick = {
        context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }) {
        Text(
            color = MaterialTheme.colors.onPrimary,
            text = stringResource(R.string.enable_bluetooth),
        )
    }
}
