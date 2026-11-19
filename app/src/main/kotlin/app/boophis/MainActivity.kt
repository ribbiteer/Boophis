// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.boophis.model.LayoutMode
import app.boophis.model.Route
import app.boophis.model.ScanReason
import app.boophis.ui.BoophisRoot
import app.boophis.ui.FolderPicker

class MainActivity : ComponentActivity() {
    private val app get() = application as BoophisApp
    private var hasAccess by mutableStateOf(false)

    private var pickCallback: ((String?) -> Unit)? = null
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val cb = pickCallback
        pickCallback = null
        // Cancelled: say nothing. Chosen but not a plain phone / SD card folder: null, so the UI explains.
        if (uri != null) cb?.invoke(treeUriToPath(uri))
    }
    private val folderPicker = object : FolderPicker {
        override fun pick(onResult: (String?) -> Unit) {
            pickCallback = onResult
            treePicker.launch(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasAccess = app.library.hasStorageAccess()
        setContent {
            val nav = app.phoneNavigator
            LaunchedEffect(nav) {
                snapshotFlow { (nav.current == Route.Player) to debugOrientation }.collect { (onPlayer, _) -> setPlayerMode(onPlayer) }
            }
            BoophisRoot(
                deps = remember { app.uiDeps.withFolderPicker(folderPicker) },
                mode = LayoutMode.Phone,
                navigator = nav,
                videoSurface = { m -> app.VideoSurface(m) },
                onRequestStorageAccess = ::requestStorageAccess,
                hasStorageAccess = hasAccess,
            )
        }
        PhoneSnapshot.root = window.decorView
    }

    override fun onResume() {
        super.onResume()
        if (app.phoneNavigator.current != Route.Player) requestedOrientation = debugOrientation
        val now = app.library.hasStorageAccess()
        if (now && !hasAccess) {
            app.library.rescan(ScanReason.APP_START)
            requestNotificationsOnce()
        }
        hasAccess = now
        app.carReady.onResume()
    }

    override fun onDestroy() {
        if (PhoneSnapshot.root === window.decorView) PhoneSnapshot.root = null
        super.onDestroy()
    }

    /** "primary:Videos/Films" -> external storage root + path; "<UUID>:path" -> /storage/<UUID>/path; else null. */
    private fun treeUriToPath(uri: Uri): String? {
        val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return null }
        val volume = id.substringBefore(':', "")
        val rel = id.substringAfter(':', "").trim('/')
        val root = when {
            volume.isEmpty() -> return null
            volume == "primary" -> Environment.getExternalStorageDirectory().path
            Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|[0-9A-Fa-f-]{8,}").matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    private fun requestStorageAccess() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /** Playback notification (lock-screen controls); asked once, right after storage access is granted. */
    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("boophis_ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** Player: landscape + immersive; elsewhere: follow the user's rotation, bars visible. */
    private fun setPlayerMode(onPlayer: Boolean) {
        requestedOrientation = if (onPlayer) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else debugOrientation
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (onPlayer) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Debug snapshot hook (read by the debug-only receiver). */
object PhoneSnapshot {
    @Volatile var root: View? = null
}

/** Debug-only override for layout checks (set by the debug receiver); release never changes it. */
private val debugOrientationState = androidx.compose.runtime.mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
var debugOrientation: Int
    get() = debugOrientationState.intValue
    set(v) { debugOrientationState.intValue = v }
