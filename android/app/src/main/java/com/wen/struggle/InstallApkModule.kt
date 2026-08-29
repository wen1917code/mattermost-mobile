package com.wen.struggle

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File

class InstallApkModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "InstallApkModule"

    @ReactMethod
    fun installApk(filePath: String) {
        Log.w("InstallApkModule", "installApk called with: $filePath")
        val path = if (filePath.startsWith("file://")) {
            Uri.parse(filePath).path ?: filePath
        } else {
            filePath
        }
        val file = File(path)
        Log.w("InstallApkModule", "Resolved path: $path, exists: ${file.exists()}, size: ${if (file.exists()) file.length() else "N/A"}")

        if (!file.exists()) {
            Log.e("InstallApkModule", "File not found: $path")
            return
        }

        // Check REQUEST_INSTALL_PACKAGES permission on Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!reactContext.packageManager.canRequestPackageInstalls()) {
                Log.w("InstallApkModule", "No install permission, opening settings")
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${reactContext.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    reactContext.startActivity(settingsIntent)
                } catch (e: Exception) {
                    Log.e("InstallApkModule", "Cannot open settings: ${e.message}")
                }
                return
            }
        }

        val uri: Uri = FileProvider.getUriForFile(
            reactContext,
            "${reactContext.packageName}.provider",
            file
        )
        Log.w("InstallApkModule", "URI: $uri")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            reactContext.startActivity(intent)
            Log.w("InstallApkModule", "Install intent launched")
        } catch (e: Exception) {
            Log.e("InstallApkModule", "Launch failed: ${e.message}", e)
        }
    }
}
