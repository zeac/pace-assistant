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

import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.AnyThread
import java.util.UUID

interface CharacteristicDelegate<T> {
    /**
     * UUID of the Bluetooth GATT service containing the characteristic.
     */
    val service: UUID

    /**
     * UUID of the Bluetooth GATT characteristic to be notified about.
     */
    val characteristic: UUID

    @AnyThread
    fun parseValue(characteristic: BluetoothGattCharacteristic): T
}
