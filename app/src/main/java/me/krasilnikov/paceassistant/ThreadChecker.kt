package me.krasilnikov.paceassistant

import android.os.Looper

class ThreadChecker {
    private val initialLooper = Looper.myLooper()

    val isValid
        get() = initialLooper === Looper.myLooper()
}
