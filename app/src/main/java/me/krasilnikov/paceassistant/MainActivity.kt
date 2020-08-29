package me.krasilnikov.paceassistant

import android.bluetooth.BluetoothAdapter
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
import android.os.Bundle
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Box
import androidx.compose.foundation.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.setContent
import androidx.ui.tooling.preview.Devices
import androidx.ui.tooling.preview.Preview
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner

    private var connectedDevice: BluetoothGatt? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (Looper.getMainLooper() != Looper.myLooper()) throw RuntimeException()

            if (result == null) return
            if (connectedDevice != null) return

            connectedDevice = result.device.connectGatt(this@MainActivity, false, gattCallback)

            bluetoothLeScanner.stopScan(this)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyUpdate(gatt, txPhy, rxPhy, status)
        }

        override fun onPhyRead(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            super.onPhyRead(gatt, txPhy, rxPhy, status)
        }

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

            gatt.getService(UUID.fromString(HEART_RATE_SERVICE))?.let { heartRateService ->
                heartRateService.getCharacteristic(UUID.fromString(HEART_RATE_MEASUREMENT))
                    ?.let { characteristic ->
                        gatt.readCharacteristic(characteristic)

                        gatt.setCharacteristicNotification(characteristic, true)
                        characteristic.getDescriptor(
                            UUID.fromString(
                                CLIENT_CHARACTERISTIC_CONFIGURATION
                            )
                        )?.let { descriptor ->
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
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

            Log.e("Test", "${flag.toString(16)} $hr")

            val size = characteristic.value.size
            while (offset < size) {
                val rr =
                    characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, offset)
                val f = rr / 1024.0f
                Log.e("Test", "rr: $f")
                offset += 2
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
        }

        override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
            super.onReliableWriteCompleted(gatt, status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            super.onReadRemoteRssi(gatt, rssi, status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colors = if (isSystemInDarkTheme()) darkPalette else lightPalette,
            ) {
                Box(gravity = Alignment.Center) {
                    Text(
                        text = "Test",
                        color = MaterialTheme.colors.onSurface,
                    )
                }
            }
        }

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    }

    override fun onResume() {
        super.onResume()

        val settings = ScanSettings.Builder().build()
        val filter =
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(HEART_RATE_SERVICE)).build()
        bluetoothLeScanner.startScan(listOf(filter), settings, callback)
    }

    override fun onPause() {
        super.onPause()

        connectedDevice?.close()
        connectedDevice = null

        bluetoothLeScanner.stopScan(callback)
    }

    @Preview(widthDp = 400, heightDp = 800, showDecoration = true, device = Devices.PIXEL_3)
    @Composable
    private fun render() {
        MaterialTheme(
            colors = if (isSystemInDarkTheme()) darkPalette else lightPalette,
        ) {
            Box(gravity = Alignment.Center,
            modifier = Modifier.fillMaxWidth()) {
                Text(
                    // modifier = Modifier.gravity(align = Alignment.Horizontal),
                    text = "Test",
                    color = MaterialTheme.colors.onSurface,
                )
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
