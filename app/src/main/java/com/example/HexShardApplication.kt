package com.example

import android.app.Application
import android.util.Log
import android.widget.Toast
import timber.log.Timber
import com.example.di.AppComponent
import com.example.di.DaggerAppComponent
import com.google.firebase.FirebaseApp

class HexShardApplication : Application() {
    lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ProductionTree())
        }

        try {
            appComponent = DaggerAppComponent.factory().create(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Application startup error", Toast.LENGTH_LONG).show()
        }

        // SECURITY: Global exception handler (don't show stack traces to user)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            Timber.e(exception, "FATAL UNCAUGHT EXCEPTION in ${thread.name}")
            logCrashLocally(exception)
            defaultHandler?.uncaughtException(thread, exception)
        }
    }

    private fun logCrashLocally(exception: Throwable) {
        Timber.e("Local crash logged: ${exception.javaClass.simpleName} - ${exception.message}")
    }
}

// Release build tree - no verbose logging
private class ProductionTree : Timber.Tree() {
    private val SENSITIVE_PATTERNS = listOf(
        Regex("phone['\"]?\\s*[:=]\\s*['\"]?([\\d+]+)") to "PHONE",
        Regex("user[Ii]d['\"]?\\s*[:=]\\s*['\"]?([a-zA-Z0-9]+)") to "USER_ID",
        Regex("token['\"]?\\s*[:=]\\s*['\"]?([a-zA-Z0-9_-]+)") to "TOKEN"
    )
    
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        var cleanMessage = message
        
        // Redact sensitive data
        for ((pattern, replacement) in SENSITIVE_PATTERNS) {
            cleanMessage = cleanMessage.replace(pattern, replacement)
        }
        
        // Only log errors and warnings in production
        if (priority >= Log.WARN) {
            // FirebaseCrashlytics.getInstance().log(cleanMessage)
        }
    }
}

