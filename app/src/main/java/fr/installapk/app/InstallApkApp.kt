package fr.installapk.app

import android.app.Application
import android.util.Log

class InstallApkApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("InstallAPK", "Fatal uncaught crash on thread ${thread.name}", throwable)
            try {
                val prefs = getSharedPreferences("installapk_prefs", MODE_PRIVATE)
                prefs.edit().putString("last_crash_error", throwable.stackTraceToString()).apply()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
