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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun AssistantControl(assisting: Boolean, onAssistingChanged: (assisting: Boolean) -> Unit = {}) {
    Row(
        modifier = Modifier
            .toggleable(value = assisting, onValueChange = onAssistingChanged),
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Text(
            text = stringResource(R.string.voice_assist),
            color = MaterialTheme.colors.onSurface,
        )

        Switch(checked = assisting, onCheckedChange = onAssistingChanged)
    }
}

@Composable
@Preview(showSystemUi = true, device = Devices.PIXEL_3)
private fun AssistantControl_Preview() {
    AssistantControl(false)
}
