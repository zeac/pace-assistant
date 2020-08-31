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
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
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

object Worker {

    private val _context = PaceApplication.instance
    private val _threadCheck = ThreadChecker()
    private val _coroutineScope = CoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableLiveData<State>()

    private val _subscriptions = mutableListOf<Any>()
    private val _subscriptionsChanged = Channel<Unit>(CONFLATED)
    private val _permissionsChanged = Channel<Unit>(CONFLATED)

    val state: LiveData<State>
        get() = _state

    init {
        _coroutineScope.launch { work() }
    }

    fun subscribe(key: Any): AutoCloseable? {
        _subscriptions.add(key)
        _subscriptionsChanged.offer(Unit)

        return AutoCloseable {
            _subscriptions.remove(key)
            _subscriptionsChanged.offer(Unit)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @MainThread
    fun updatePermission() {
        _permissionsChanged.offer(Unit)
    }

    private suspend fun work() {
        require(_threadCheck.isValid)

        val bluetoothLeScanner: BluetoothLeScanner? =
            (_context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            _state.value = State.NoBluetooth
            return
        }

        while (true) {
            val havePermission = checkPermission()
            _state.value = if (havePermission) State.Scanning else State.NoPermission

            if (_subscriptions.isEmpty() || !havePermission) {
                select<Unit> {
                    _subscriptionsChanged.onReceive {
                        require(_threadCheck.isValid)
                    }
                    _permissionsChanged.onReceive {
                        require(_threadCheck.isValid)
                    }
                }
                continue
            }

            val scanJob = _coroutineScope.async { scanForResult(bluetoothLeScanner) }
            val device = select<BluetoothDevice?> {
                _subscriptionsChanged.onReceive {
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

            val gatt = device.connectGatt(_context, false, GattCallback())
            try {
                whileSelect {
                    _subscriptionsChanged.onReceive {
                        require(_threadCheck.isValid)

                        _subscriptions.isNotEmpty()
                    }

                    channel.onReceiveOrNullExt().invoke { hr ->
                        require(_threadCheck.isValid)

                        if (hr != null) {
                            _state.value = State.Heartbeat(hr)
                            true
                        } else {
                            false
                        }
                    }
                }
            } finally {
                require(_threadCheck.isValid)

                gatt.close()
            }
        }
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            _context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private suspend fun scanForResult(scanner: BluetoothLeScanner): BluetoothDevice {
        require(_threadCheck.isValid)

        return suspendCancellableCoroutine { invocation ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    require(_threadCheck.isValid)

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
                require(_threadCheck.isValid)

                scanner.stopScan(callback)
            }
        }
    }

    const val CLIENT_CHARACTERISTIC_CONFIGURATION = "00002902-0000-1000-8000-00805f9b34fb"

    const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
    const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"

    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_CHARACTERISTIC = "00002a19-0000-1000-8000-00805f9b34fb"

    const val TAG = "Pace"
}
