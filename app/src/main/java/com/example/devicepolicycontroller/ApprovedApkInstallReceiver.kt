package com.example.devicepolicycontroller

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.IOException

/**
 * A narrowly scoped installation gateway for allowlisted apps.
 *
 * An approved app sends an explicit broadcast with [Intent.data] set to a readable APK content URI
 * and FLAG_GRANT_READ_URI_PERMISSION. On Android 14+ the DPC checks Android's authenticated
 * broadcast sender before copying the APK into a PackageInstaller session.
 */
class ApprovedApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Android 14+ exposes the authenticated sender package. Older versions cannot safely
        // support a package allowlist for an exported broadcast receiver, so reject them.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val sender = getSentFromPackage() ?: return
        val allowed = context.getSharedPreferences("controller_security", Context.MODE_PRIVATE)
            .getStringSet("approved_installers", emptySet()).orEmpty()
        if (sender !in allowed) return
        val uri = intent.data ?: return
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val pending = goAsync()
        Thread {
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val installer = context.packageManager.packageInstaller
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        session.openWrite("approved.apk", 0, -1).use { output -> input.copyTo(output) }
                    } ?: throw IOException("Cannot read the supplied APK URI")
                    val status = Intent(context, InstallStatusReceiver::class.java)
                    val callback = PendingIntent.getBroadcast(
                        context, sessionId, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    session.commit(callback.intentSender)
                }
            } catch (error: Exception) {
                notify(context, "Approved APK install failed: ${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notify(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "APK installation was not completed.", Toast.LENGTH_LONG).show()
        }
    }
}
