package dev.nutting.pocketllm

import android.app.Application

class PocketLlmApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
