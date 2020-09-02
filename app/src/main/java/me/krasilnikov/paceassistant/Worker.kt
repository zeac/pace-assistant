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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.whileSelect
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
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

            val channel = Channel<Int>(CONFLATED)

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

                    Log.e(TAG, "${flag.toString(16)} $hr")
                    channel.offer(hr)

                    val size = characteristic.value.size
                    while (offset < size) {
                        val rr =
                            characteristic.getIntValue(
                                BluetoothGattCharacteristic.FORMAT_UINT16,
                                offset
                            )
                        val f = rr / 1024.0f
                        Log.e(TAG, "rr: $f")
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

                    channel.onReceiveOrNullExt().invoke { hr ->
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
