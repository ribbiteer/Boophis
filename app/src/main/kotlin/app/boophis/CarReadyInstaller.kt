// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import app.boophis.ui.CarReadyPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Make Boophis car-ready": reinstalls Boophis's own APK through the phone's stock Package Installer,
 * attributed to Google Play — the same thing KingInstaller's non-root mode does.
 *
 * Android Auto in a real car hides sideloaded apps unless installerPackageName is
 * com.android.vending AND the install was requested through the stock installer (not adb's shell).
 * The stock installer copies [Intent.EXTRA_INSTALLER_PACKAGE_NAME] into the install session, so any
 * app allowed to request installs can do this for itself. No network, no root, no Shizuku.
 */
class CarReadyInstaller(private val context: Context, private val scope: CoroutineScope) : CarReadyPrompt {
    private val _needed = MutableStateFlow(false)
    override val needed: StateFlow<Boolean> = _needed

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy

    init { refresh() }

    fun refresh() {
        val src = installSource()
        _needed.value = !src.carReady && selfApk() != null
        Log.i(TAG, "install source: installer=${src.installer} initiating=${src.initiating} carReady=${src.carReady}")
    }

    /** Called from MainActivity.onResume (the install may have been cancelled or replaced us). */
    fun onResume() = refresh()

    /**
     * Hands the APK straight to the stock installer. If "Install unknown apps" isn't granted yet,
     * the installer itself shows Android's prompt with a Settings shortcut to Boophis's own switch and
     * continues the install afterwards (Samsung's settings intent only opens the full app list).
     */
    override fun start() {
        if (_busy.value) return
        val apk = selfApk() ?: return
        _busy.value = true
        scope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, DIR).apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, "boophis.apk").also { apk.copyTo(it, overwrite = true) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.selfinstall", copy)
                @Suppress("DEPRECATION")
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY)
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                // Always the system's own installer: other apps (e.g. KingInstaller) also handle this
                // intent, and AA only accepts installs the stock installer requested.
                stockInstaller(intent)?.let { intent.setPackage(it) }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "self-reinstall failed to start", e)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun stockInstaller(intent: Intent): String? =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
            .firstOrNull()?.activityInfo?.packageName

    /** Boophis's own base APK; null for split installs (can't be reinstalled from one file). */
    private fun selfApk(): File? {
        val info = context.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return null
        return File(info.sourceDir).takeIf { it.canRead() }
    }

    private data class Source(val installer: String?, val initiating: String?) {
        val carReady get() = installer == PLAY && initiating != null && initiating != SHELL
    }

    private fun installSource(): Source = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val s = context.packageManager.getInstallSourceInfo(context.packageName)
            Source(s.installingPackageName, s.initiatingPackageName)
        } else {
            @Suppress("DEPRECATION")
            val installer = context.packageManager.getInstallerPackageName(context.packageName)
            Source(installer, installer) // API 29 can't tell who initiated; trust the installer
        }
    } catch (e: Exception) {
        Source(null, null)
    }

    private companion object {
        const val TAG = "BoophisCarReady"
        const val PLAY = "com.android.vending"
        const val SHELL = "com.android.shell"
        const val DIR = "selfinstall"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
