package me.krasilnikov.paceassistant

import android.app.Application

class PaceApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        instance = this
    }

    companion object {
        lateinit var instance: Application
    }
}