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

package me.krasilnikov.paceassistant.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import me.krasilnikov.paceassistant.ThreadChecker
import timber.log.Timber
import kotlin.coroutines.resume

class BluetoothHelper(private val context: Context) {
    private val threadCheck = ThreadChecker()

    /**
     * Suspend execution until bluetooth state changes to the given value.
     */
    suspend fun waitForChange(newState: Int) {
        Timber.tag(TAG).i(".waitForChange: %d", newState)

        val stateChanged = Channel<Unit>(Channel.CONFLATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                require(threadCheck.isValid)

                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)

                Timber.tag(TAG).i(".waitForChange: bluetooth state had changed to %d", state)

                if (state == newState) {
                    stateChanged.trySend(Unit).isSuccess
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

    @SuppressLint("MissingPermission")
    suspend fun scanForFirst(scanner: BluetoothLeScanner, filter: ScanFilter): BluetoothDevice {
        Timber.tag(TAG).i(".scanForFirst:")

        return suspendCancellableCoroutine { invocation ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    require(threadCheck.isValid)

                    Timber.tag(TAG).i(".onScanResult: callbackType = %d %s", callbackType, result)

                    if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) return
                    if (result == null) return

                    scanner.stopScan(this)

                    if (invocation.isActive) invocation.resume(result.device)
                }
            }

            val settings = ScanSettings.Builder().build()
            scanner.startScan(listOf(filter), settings, callback)

            invocation.invokeOnCancellation {
                require(threadCheck.isValid)

                try {
                    scanner.stopScan(callback)
                } catch (e: IllegalStateException) {
                    // It would be better if stopScan was idempotent.
                }
            }
        }
    }

    companion object {
        const val TAG = "BluetoothHelper"
    }
}
