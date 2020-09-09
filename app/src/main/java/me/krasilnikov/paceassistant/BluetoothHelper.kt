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

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.channels.Channel

class BluetoothHelper(private val context: Context) {
    private val threadCheck = ThreadChecker()

    /**
     * Suspend execution until bluetooth state changes to the given value.
     */
    suspend fun waitForChange(newState: Int) {
        Log.i("Pace", "BluetoothHelper.waitForChange: $newState")

        val stateChanged = Channel<Unit>(Channel.CONFLATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                require(threadCheck.isValid)

                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)

                Log.i("Pace", "BluetoothHelper.waitForChange: bluetooth state had changed to $state")

                if (state == newState) {
                    stateChanged.offer(Unit)
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        try {
            stateChanged.receive()
        } finally {
            require(threadCheck.isValid)

            context.unregisterReceiver(receiver)
        }
    }
}
