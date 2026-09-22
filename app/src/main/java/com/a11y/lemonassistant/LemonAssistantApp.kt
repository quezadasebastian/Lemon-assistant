package com.a11y.lemonassistant

import android.app.Application
import com.a11y.lemonassistant.security.SecurePinStorage

class LemonAssistantApp : Application() {

    lateinit var securePinStorage: SecurePinStorage
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        securePinStorage = SecurePinStorage(this)
    }

    companion object {
        lateinit var instance: LemonAssistantApp
            private set
    }
}
