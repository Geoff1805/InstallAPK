package fr.installapk.app

import android.app.Application
import android.util.Log
import fr.installapk.app.util.NotificationHelper
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

class InstallApkApp : Application() {
    override fun onCreate() {
        super.onCreate()

        NotificationHelper.initChannels(this)

        if (BuildConfig.SENTRY_DSN.isNotBlank()) {
            try {
                SentryAndroid.init(this) { options ->
                    options.dsn = BuildConfig.SENTRY_DSN
                    options.tracesSampleRate = 0.2
                    options.environment = if (BuildConfig.DEBUG) "debug" else "production"
                }
            } catch (e: Exception) {
                Log.w("InstallAPK", "Sentry init ignored: ${e.message}")
            }
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("InstallAPK", "Fatal uncaught crash on thread ${thread.name}", throwable)
            try {
                if (BuildConfig.SENTRY_DSN.isNotBlank()) {
                    Sentry.captureException(throwable)
                }
                val prefs = getSharedPreferences("installapk_prefs", MODE_PRIVATE)
                prefs.edit().putString("last_crash_error", throwable.stackTraceToString()).apply()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
