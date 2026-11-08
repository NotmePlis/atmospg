package com.atmosphere.companion

import android.app.Application

class CompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RelayEngine.appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        Notifications.ensureChannel(this)
        SyncWorker.schedule(this)
    }
}
