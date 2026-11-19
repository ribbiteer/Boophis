// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import app.boophis.car.CarSnapshots
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the car Presentation's view tree and the phone activity's view tree to PNGs in
 * getExternalFilesDir("snapshots") → /sdcard/Android/data/io.github.ribbiteer.boophis/files/snapshots/.
 * Note: SurfaceView video content renders black in a View.draw snapshot.
 */
object DebugSnapshot {
    fun capture(context: Context) {
        Handler(Looper.getMainLooper()).post {
            val dir = File(context.getExternalFilesDir(null), "snapshots").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            CarSnapshots.currentCarRoot()?.let { save(it, File(dir, "car-$stamp.png")) }
            app.boophis.PhoneSnapshot.root?.let { save(it, File(dir, "phone-$stamp.png")) }
        }
    }

    private fun save(view: View, file: File) {
        if (view.width <= 0 || view.height <= 0) return
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        Log.i("BoophisDebug", "snapshot ${file.absolutePath}")
    }
}
