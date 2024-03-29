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
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import kotlinx.coroutines.channels.Channel
import me.krasilnikov.paceassistant.ThreadChecker
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission")
class BluetoothDeviceHelper(
    private val context: Context,
    private val device: BluetoothDevice,
) : BluetoothGattCallback() {
    private val threadCheck = ThreadChecker()
    private val handler = Handler(Looper.myLooper()!!)

    private val subscriptions = CopyOnWriteArrayList<CharacteristicSubscription<*>>()
    private var gattClient: BluetoothGatt? = null

    fun <T> subscribe(delegate: CharacteristicDelegate<T>): Channel<T> {
        require(threadCheck.isValid)

        if (gattClient == null) {
            gattClient = device.connectGatt(context, false, this)
        }

        val channel = Channel<T>(Channel.CONFLATED)
        val s = CharacteristicSubscription(delegate, channel)

        subscriptions.add(s)

        gattClient?.let {
            if (it.services.isNotEmpty()) {
                s.enableNotifications(it)
            }
        }


        return channel
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        Timber.tag(TAG).i(".onConnectionStateChanged: status = %d newState = %d", status, newState)

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices()

        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            handler.post {
                subscriptions.forEach { it.close() }
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Timber.tag(TAG).i(".onServicesDiscovered: status = %d", status)

        if (status != BluetoothGatt.GATT_SUCCESS) return

        handler.post {
            subscriptions.forEach { it.enableNotifications(gatt) }
        }
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        subscriptions.forEach {
            if (characteristic.service.uuid == it.service && characteristic.uuid == it.characteristic) {
                it.handleValue(characteristic)
            }
        }
    }

    fun close() {
        require(threadCheck.isValid)

        gattClient?.close()
        gattClient = null
    }

    inner class CharacteristicSubscription<T>(
        private val delegate: CharacteristicDelegate<T>,
        private val channel: Channel<T>,
    ) {

        val service: UUID = delegate.service

        val characteristic: UUID = delegate.characteristic

        fun enableNotifications(gatt: BluetoothGatt) {
            gatt.getService(delegate.service)?.let { heartRateService ->
                heartRateService.getCharacteristic(delegate.characteristic)
                    ?.let { characteristic ->
                        gatt.readCharacteristic(characteristic)

                        gatt.setCharacteristicNotification(characteristic, true)
                        characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION)?.let { descriptor ->
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
            }
        }

        fun close() {
            channel.close()
        }

        @AnyThread
        fun handleValue(characteristic: BluetoothGattCharacteristic) {
            val value = delegate.parseValue(characteristic)

            channel.trySend(value).isSuccess
        }
    }

    companion object {
        private const val TAG = "BluetoothDeviceHelper"

        private val CLIENT_CHARACTERISTIC_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
