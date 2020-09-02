package me.krasilnikov.paceassistant

sealed class State {
    object NoBluetooth : State()
    object NoPermission : State()
    object Scanning : State()
    data class Monitor(val beat: Int) : State()
    data class Assist(val beat: Int, val assistStartTime: Long) : State()
}
