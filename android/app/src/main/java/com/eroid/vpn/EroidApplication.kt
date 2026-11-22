package com.eroid.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream

class EroidApplication : Application() {

    companion object {
        const val CHANNEL_ID = "eroid_vpn_channel"
        lateinit var instance: EroidApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        extractRuleSets()
    }

    fun extractRuleSets() {
        try {
            val rulesDir = File(filesDir, "rules")
            if (!rulesDir.exists()) rulesDir.mkdirs()
            listOf("geoip-ru.srs", "geosite-category-ru.srs").forEach { fileName ->
                val destFile = File(rulesDir, fileName)
                if (!destFile.exists() || destFile.length() == 0L) {
                    assets.open("rules/$fileName").use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
