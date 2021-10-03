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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.whileSelect
import me.krasilnikov.paceassistant.audio.AudioFocus26
import me.krasilnikov.paceassistant.audio.AudioFocus8
import me.krasilnikov.paceassistant.audio.createAnnouncer
import me.krasilnikov.paceassistant.bluetooth.BluetoothDeviceHelper
import me.krasilnikov.paceassistant.bluetooth.BluetoothHelper
import me.krasilnikov.paceassistant.bluetooth.CharacteristicDelegate
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@MainThread
object Worker {

    private val context = PaceApplication.instance
    private val threadCheck = ThreadChecker()
    private val coroutineScope = CoroutineScope(Dispatchers.Main.immediate)
    private val bluetoothHelper = BluetoothHelper(context)

    private val _state = MutableStateFlow<State>(State.Scanning)

    private val subscriptions = mutableListOf<Any>()
    private val subscriptionsChanged = Channel<Unit>(CONFLATED)
    private val permissionsChanged = Channel<Unit>(CONFLATED)
    private val assistingChanged = Channel<Unit>(CONFLATED)

    private val vocalizeChannel = Channel<Int>(CONFLATED)

    val assisting = MutableLiveData(true).apply {
        observeForever {
            assistingChanged.trySend(Unit).isSuccess
        }
    }

    val state: StateFlow<State> = _state.asStateFlow()

    init {
        launchJob()
    }

    fun subscribe(key: Any): AutoCloseable {
        subscriptions.add(key)
        subscriptionsChanged.trySend(Unit).isSuccess

        return AutoCloseable {
            subscriptions.remove(key)
            subscriptionsChanged.trySend(Unit).isSuccess
        }
    }

    /**
     * To be called when user has asked to stop assistant.
     */
    fun stop(stopService: Boolean) {
        require(threadCheck.isValid)

        assisting.value = false

        if (stopService) {
            stopForegroundService()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun updatePermission() {
        require(threadCheck.isValid)

        permissionsChanged.trySend(Unit).isSuccess
    }

    private fun launchJob() = coroutineScope.launch {
        require(threadCheck.isValid)

        Timber.tag(TAG).i(".launchJob: start job")

        val bluetoothAdapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bluetoothAdapter == null) {
            Timber.tag(TAG).e("No bluetooth available")
            _state.value = State.NoBluetooth
            return@launch
        }

        launchAnnouncer()

        while (true) {
            if (!bluetoothAdapter.isEnabled) {
                _state.value = State.BluetoothIsTurnedOff

                bluetoothHelper.waitForChange(BluetoothAdapter.STATE_ON)
            }

            val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner ?: continue

            // Launch the actual job to be able to cancel it when bluetooth is disabled.
            val listenJob = launch { scanAndListen(bluetoothLeScanner) }

            bluetoothHelper.waitForChange(BluetoothAdapter.STATE_OFF)

            listenJob.cancelAndJoin()
        }
    }

    private suspend fun scanAndListen(bluetoothLeScanner: BluetoothLeScanner) {
        require(threadCheck.isValid)

        while (true) {
            if (!checkPermission()) {
                _state.value = State.NoPermission

                Timber.tag(TAG).i(".scanAndLister: waiting for permission")
                permissionsChanged.receive()
                continue
            }

            _state.value = State.Scanning

            if (subscriptions.isEmpty()) {
                Timber.tag(TAG).i(".scanAndLister: waiting for subscriptions")
                subscriptionsChanged.receive()

                continue
            }

            val scanJob = scanForResultAsync(bluetoothLeScanner)
            val device = select<BluetoothDevice?> {
                subscriptionsChanged.onReceive { null }
                scanJob.onAwait { it }
            }
            if (device == null) {
                Timber.tag(TAG).i(".scanAndListen: scanning was canceled")
                scanJob.cancelAndJoin()
                continue
            }

            val deviceHelper = BluetoothDeviceHelper(context, device)
            val beatChannel = deviceHelper.subscribe(object : CharacteristicDelegate<Int> {
                override val service = HEART_RATE_SERVICE
                override val characteristic = HEART_RATE_MEASUREMENT

                @AnyThread
                override fun parseValue(characteristic: BluetoothGattCharacteristic): Int {
                    var offset = 1

                    val flag =
                        characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                    val hr = if (flag and 0x1 == 0) {
                        offset += 1
                        characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
                    } else {
                        offset += 2
                        characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 1)
                    }

                    Timber.tag(TAG).i("onCharacteristicChanged: flags = %s hr = %d", flag.toString(16), hr)

                    return hr
                }

            })

            try {
                var startTime = 0L
                var startTimeUTC = 0L
                var lastHeartbeat = 0

                fun updateState(hr: Int) {
                    lastHeartbeat = hr
                    if (assisting.value == true) {
                        if (startTime == 0L) {
                            startTime = SystemClock.elapsedRealtime()
                            startTimeUTC = System.currentTimeMillis()
                        }

                        _state.value = State.Assist(beat = hr, deviceName = device.name, assistStartTime = startTime)

                        vocalizeChannel.trySend(hr).isSuccess
                    } else {
                        startTime = 0L

                        _state.value = State.Monitor(beat = hr, deviceName = device.name)
                    }
                }

                whileSelect {
                    subscriptionsChanged.onReceive {
                        require(threadCheck.isValid)

                        if (subscriptions.isEmpty()) {
                            if (assisting.value == true) {
                                // There is no activity on the screen but an assistant is needed.
                                startForegroundService(startTimeUTC)
                                true
                            } else {
                                false
                            }
                        } else {
                            true
                        }
                    }

                    assistingChanged.onReceive {
                        if (lastHeartbeat > 0) {
                            updateState(lastHeartbeat)
                        }
                        // The user asked to stop, but the updates are still required
                        // for the data on the screen.
                        subscriptions.isNotEmpty() || assisting.value == true
                    }

                    beatChannel.onReceiveCatching { hr ->
                        require(threadCheck.isValid)

                        if (hr.isSuccess) {
                            updateState(hr.getOrThrow())

                            true
                        } else {
                            false
                        }
                    }
                }
            } finally {
                require(threadCheck.isValid)

                deviceHelper.close()
                // gatt.close()
            }
        }
    }

    private fun launchAnnouncer() = coroutineScope.launch {
        require(threadCheck.isValid)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val audioSessionId = audioManager.generateAudioSessionId()
        val focusHelper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocus26(context)
        } else {
            AudioFocus8(context)
        }
        val announcer = createAnnouncer(context, audioSessionId)

        var lastAnnounceTime = 0L
        var lastAnnounceHR = 0 // Beats per 6 seconds.
        for (hr in vocalizeChannel) {
            require(threadCheck.isValid)

            val now = SystemClock.elapsedRealtime()

            val update = when {
                lastAnnounceTime == 0L -> true
                now - lastAnnounceTime > TimeUnit.SECONDS.toMillis(30) -> true
                abs(lastAnnounceHR * 10 - hr) > 7 -> true
                else -> false
            }

            if (update) {
                val sixsecond = hr / 10 + if ((hr % 10) > 5) 1 else 0

                focusHelper.withFocus {
                    delay(200)

                    announcer.say(sixsecond)
                }

                lastAnnounceHR = sixsecond
                lastAnnounceTime = now
            }
        }
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun scanForResultAsync(scanner: BluetoothLeScanner) = coroutineScope.async {
        require(threadCheck.isValid)

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE))
                .build()
        bluetoothHelper.scanForFirst(scanner, filter)
    }

    private fun startForegroundService(startTime: Long) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ForegroundService::class.java).apply {
                if (startTime > 0L) putExtra("when", startTime)
            }
        )
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, ForegroundService::class.java))
    }

    private val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    private const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    private const val BATTERY_CHARACTERISTIC = "00002a19-0000-1000-8000-00805f9b34fb"

    private const val TAG = "Worker"
}
