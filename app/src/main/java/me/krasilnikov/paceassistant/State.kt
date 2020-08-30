package me.krasilnikov.paceassistant

sealed class State {
    object NoBluetooth : State()
    object NoPermission : State()
    object Idle : State()
    object Scanning : State()
    data class Heartbeat(val beat: Int) : State()
}