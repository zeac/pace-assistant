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
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.whileSelect
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.channels.onReceiveOrNull as onReceiveOrNullExt

@MainThread
object Worker {

    private val context = PaceApplication.instance
    private val threadCheck = ThreadChecker()
    private val coroutineScope = CoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableLiveData<State>()

    private val subscriptions = mutableListOf<Any>()
    private val subscriptionsChanged = Channel<Unit>(CONFLATED)
    private val permissionsChanged = Channel<Unit>(CONFLATED)
    private val assistingChanged = Channel<Unit>(CONFLATED)

    val assisting = MutableLiveData(true).apply {
        observeForever {
            assistingChanged.offer(Unit)
        }
    }

    val state: LiveData<State>
        get() = _state

    init {
        coroutineScope.launch { work() }
    }

    fun subscribe(key: Any): AutoCloseable? {
        subscriptions.add(key)
        subscriptionsChanged.offer(Unit)

        return AutoCloseable {
            subscriptions.remove(key)
            subscriptionsChanged.offer(Unit)
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

        permissionsChanged.offer(Unit)
    }

    private suspend fun work() {
        require(threadCheck.isValid)

        val bluetoothLeScanner: BluetoothLeScanner? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            _state.value = State.NoBluetooth
            return
        }

        val vocalize = Channel<Int>(CONFLATED)
        coroutineScope.launch { vocalizer(vocalize) }

        while (true) {
            val havePermission = checkPermission()
            _state.value = if (havePermission) State.Scanning else State.NoPermission

            if (subscriptions.isEmpty() || !havePermission) {
                select<Unit> {
                    subscriptionsChanged.onReceive {
                        require(threadCheck.isValid)
                    }
                    permissionsChanged.onReceive {
                        require(threadCheck.isValid)
                    }
                }
                continue
            }

            val scanJob = coroutineScope.async { scanForResult(bluetoothLeScanner) }
            val device = select<BluetoothDevice?> {
                subscriptionsChanged.onReceive {
                    scanJob.cancelAndJoin()
                    null
                }
                scanJob.onAwait { it }
            } ?: continue

            val beatChannel = Channel<Int>(CONFLATED)

            class GattCallback : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int
                ) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) return

                    /*
                    gatt.getService(UUID.fromString(BATTERY_SERVICE))?.let { batteryService ->
                        batteryService.getCharacteristic(UUID.fromString(BATTERY_CHARACTERISTIC))?.let { characteristic ->
                            gatt.readCharacteristic(characteristic)
                        }
                    }
                     */

                    gatt.getService(UUID.fromString(HEART_RATE_SERVICE))?.let { heartRateService ->
                        heartRateService.getCharacteristic(UUID.fromString(HEART_RATE_MEASUREMENT))
                            ?.let { characteristic ->
                                gatt.readCharacteristic(characteristic)

                                gatt.setCharacteristicNotification(characteristic, true)
                                characteristic.getDescriptor(
                                    UUID.fromString(CLIENT_CHARACTERISTIC_CONFIGURATION)
                                )?.let { descriptor ->
                                    descriptor.value =
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(descriptor)
                                }
                            }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid != UUID.fromString(HEART_RATE_MEASUREMENT)) return

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

                    if (flag and 0x8 == 1) offset += 2

                    Log.i(TAG, "${flag.toString(16)} $hr")
                    beatChannel.offer(hr)

                    val size = characteristic.value.size
                    while (offset < size) {
                        val rr =
                            characteristic.getIntValue(
                                BluetoothGattCharacteristic.FORMAT_UINT16,
                                offset
                            )
                        val f = rr / 1024.0f
                        Log.i(TAG, "rr: $f")
                        offset += 2
                    }
                }
            }

            val gatt = device.connectGatt(context, false, GattCallback())
            try {
                var startTime = 0L
                var lastHeartbeat = 0

                fun updateState(hr: Int) {
                    lastHeartbeat = hr
                    if (assisting.value == true) {
                        if (startTime == 0L) startTime = SystemClock.elapsedRealtime()

                        _state.value = State.Assist(hr, startTime)

                        vocalize.offer(hr)
                    } else {
                        startTime = 0L

                        _state.value = State.Monitor(hr)
                    }
                }

                whileSelect {
                    subscriptionsChanged.onReceive {
                        require(threadCheck.isValid)

                        if (subscriptions.isEmpty()) {
                            if (assisting.value == true) {
                                // There is no activity on the screen but an assistant is needed.
                                startForegroundService()
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

                    beatChannel.onReceiveOrNullExt().invoke { hr ->
                        require(threadCheck.isValid)

                        if (hr != null) {
                            updateState(hr)

                            true
                        } else {
                            false
                        }
                    }
                }
            } finally {
                require(threadCheck.isValid)

                gatt.close()
            }
        }
    }

    private suspend fun vocalizer(input: Channel<Int>) {
        require(threadCheck.isValid)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val audioAttributes = Audio.audioAttributes
        val audioSessionId = audioManager.generateAudioSessionId()
        val oneAsync = coroutineScope.async(Dispatchers.IO + SupervisorJob()) {
            MediaPlayer.create(
                context,
                R.raw.h1,
                audioAttributes,
                audioSessionId
            )
        }
        val tensAsync = coroutineScope.async(Dispatchers.IO + SupervisorJob()) {
            arrayOf(
                MediaPlayer.create(
                    context,
                    R.raw.h10,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h20,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h30,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h40,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h50,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h60,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h70,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h80,
                    audioAttributes,
                    audioSessionId
                ),
                MediaPlayer.create(
                    context,
                    R.raw.h90,
                    audioAttributes,
                    audioSessionId
                ),
            )
        }
        val hundredAsync = coroutineScope.async(Dispatchers.IO + SupervisorJob()) {
            MediaPlayer.create(
                context,
                R.raw.h100,
                audioAttributes,
                audioSessionId
            )
        }

        val focusHelper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocus26(context)
        } else {
            AudioFocus8(context)
        }

        var lastTime = 0L
        var lastHR = 0
        for (hr in input) {
            require(threadCheck.isValid)

            val now = SystemClock.elapsedRealtime()

            val update = when {
                lastTime == 0L -> true
                now - lastTime > TimeUnit.SECONDS.toMillis(10) -> true
                abs(lastHR - hr) > 10 -> true
                else -> false
            }

            if (update) {
                val first = (hr / 100) % 10
                val second = (hr / 10) % 10

                Log.e(TAG, "$hr say: ${first * 100 + second * 10}")

                if (first == 1 && second == 0) {
                    if (!hundredAsync.isCompleted) continue

                    focusHelper.withFocus {
                        playSound(hundredAsync.getCompleted())
                    }

                } else if (first == 1) {
                    if (!oneAsync.isCompleted) continue
                    if (!tensAsync.isCompleted) continue

                    focusHelper.withFocus {
                        playSound(oneAsync.getCompleted())
                        playSound(tensAsync.getCompleted()[second - 1])
                    }

                } else if (first == 0) {
                    if (!tensAsync.isCompleted) continue

                    focusHelper.withFocus {
                        playSound(tensAsync.getCompleted()[second - 1])
                    }
                }

                lastHR = first * 100 + second * 10
                lastTime = now
            }
        }
    }

    private suspend fun playSound(sound: MediaPlayer) {
        suspendCancellableCoroutine<Unit> { c ->
            sound.setOnSeekCompleteListener { c.resume(Unit) }
            sound.seekTo(0)
        }

        suspendCancellableCoroutine<Unit> { c ->
            sound.setOnCompletionListener { c.resume(Unit) }
            sound.start()
        }
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private suspend fun scanForResult(scanner: BluetoothLeScanner): BluetoothDevice {
        require(threadCheck.isValid)

        return suspendCancellableCoroutine { invocation ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    require(threadCheck.isValid)

                    if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) return
                    if (result == null) return

                    scanner.stopScan(this)

                    if (invocation.isActive) invocation.resume(result.device)
                }
            }

            val settings = ScanSettings.Builder().build()
            val filter =
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(HEART_RATE_SERVICE))
                    .build()
            scanner.startScan(listOf(filter), settings, callback)

            invocation.invokeOnCancellation {
                require(threadCheck.isValid)

                scanner.stopScan(callback)
            }
        }
    }

    private fun startForegroundService() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ForegroundService::class.java)
        )
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, ForegroundService::class.java))
    }

    const val CLIENT_CHARACTERISTIC_CONFIGURATION = "00002902-0000-1000-8000-00805f9b34fb"

    const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
    const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"

    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_CHARACTERISTIC = "00002a19-0000-1000-8000-00805f9b34fb"

    const val TAG = "Pace"
}
