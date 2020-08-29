package me.krasilnikov.paceassistant

import android.Manifest
import android.app.Application
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
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _heartbeat = MutableLiveData<Int>()
    private val _permission = object : MutableLiveData<Boolean>() {
        override fun onActive() {
            value = checkPermission()
        }
    }

    private val _subscriptions = mutableListOf<Any>()

    private val bluetoothLeScanner: BluetoothLeScanner? =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
    private var connectedDevice: BluetoothGatt? = null

    val heartbeat: LiveData<Int>
        get() = _heartbeat

    val permission: LiveData<Boolean>
        get() = _permission

    val bluetoothAvailable
        get() = bluetoothLeScanner != null

    fun subscribe(key: Any): AutoCloseable? {
        return bluetoothLeScanner?.let { scanner ->
            _subscriptions.add(key)

            if (checkPermission() && connectedDevice == null) {
                startScan(scanner)
            }

            AutoCloseable {
                _subscriptions.remove(key)
                tryToStop()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @MainThread
    fun updatePermission() {
        if (checkPermission()) {
            bluetoothLeScanner?.let { scanner ->
                startScan(scanner)
            }
            _permission.value = true
        }
    }

    override fun onCleared() {
        require(_subscriptions.isEmpty())
    }

    private fun startScan(scanner: BluetoothLeScanner) {
        require(_subscriptions.isNotEmpty())
        require(connectedDevice == null)

        val settings = ScanSettings.Builder().build()
        val filter =
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(HEART_RATE_SERVICE))
                .build()
        scanner.startScan(listOf(filter), settings, callback)
    }

    private fun tryToStop() {
        connectedDevice?.close()
        connectedDevice = null

        bluetoothLeScanner?.stopScan(callback)
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getApplication<Application>()
                .checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            require(Looper.getMainLooper() == Looper.myLooper())

            if (callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST) return
            if (result == null) return

            if (connectedDevice != null) return

            connectedDevice = result.device.connectGatt(application, false, gattCallback)

            bluetoothLeScanner!!.stopScan(this)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt!!.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null) return

            /*
            gatt.getService(UUID.fromString(BATTERY_SERVICE))?.let { batteryService ->
                batteryService.getCharacteristic(UUID.fromString(BATTERY_CHARACTERISTIC))?.let { characteristic ->
                    gatt.readCharacteristic(characteristic)
                }
            }
             */

            gatt.getService(UUID.fromString(HEART_RATE_SERVICE))
                ?.let { heartRateService ->
                    heartRateService.getCharacteristic(UUID.fromString(HEART_RATE_MEASUREMENT))
                        ?.let { characteristic ->
                            gatt.readCharacteristic(characteristic)

                            gatt.setCharacteristicNotification(characteristic, true)
                            characteristic.getDescriptor(
                                UUID.fromString(CLIENT_CHARACTERISTIC_CONFIGURATION)
                            )?.let { descriptor ->
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != UUID.fromString(HEART_RATE_MEASUREMENT)) return

            var offset = 1

            val flag = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
            val hr = if (flag and 0x1 == 0) {
                offset += 1
                characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
            } else {
                offset += 2
                characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 1)
            }

            if (flag and 0x8 == 1) offset += 2

            Log.e("Pace", "${flag.toString(16)} $hr")
            _heartbeat.postValue(hr)

            val size = characteristic.value.size
            while (offset < size) {
                val rr =
                    characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, offset)
                val f = rr / 1024.0f
                Log.e("Pace", "rr: $f")
                offset += 2
            }
        }
    }

    companion object {
        const val CLIENT_CHARACTERISTIC_CONFIGURATION = "00002902-0000-1000-8000-00805f9b34fb"

        const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"

        const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        const val BATTERY_CHARACTERISTIC = "00002a19-0000-1000-8000-00805f9b34fb"
    }
}
