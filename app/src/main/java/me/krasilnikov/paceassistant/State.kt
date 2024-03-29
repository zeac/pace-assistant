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

interface HeartbeatState {
    /**
     * Current HR.
     */
    val beat: Int

    /**
     * Name of the device the data is taken from.
     */
    val deviceName: String
}

sealed class State {
    /**
     * The phone have no bluetooth at all.
     */
    data object NoBluetooth : State()

    /**
     * Bluetooth adapter is available but it is turned off.
     */
    data object BluetoothIsTurnedOff : State()

    /**
     * No location permission is granted.
     */
    data object NoPermission : State()

    /**
     * A scanning in progress.
     */
    data object Scanning : State()

    /**
     * Heartbeat sensor is connected, but the assistant is disabled.
     */
    data class Monitor(override val beat: Int, override val deviceName: String) : State(), HeartbeatState

    /**
     * Heartbeat sensor is connected, the assistant is enabled.
     * @param assistStartTime the time when assisting was started.
     */
    data class Assist(override val beat: Int, override val deviceName: String, val assistStartTime: Long) :
        State(), HeartbeatState
}
