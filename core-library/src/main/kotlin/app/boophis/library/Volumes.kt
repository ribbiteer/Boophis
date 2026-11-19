// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** Mounted storage volumes (internal + SD) and the library roots found on them. */
internal object Volumes {
    fun volumes(context: Context): List<VolumeInfo> {
        val sm = context.getSystemService(StorageManager::class.java)
        val out = ArrayList<VolumeInfo>()
        for (v in sm?.storageVolumes.orEmpty()) {
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            val dir = directoryOf(v) ?: continue
            out.add(VolumeInfo(File(dir.path), if (v.isPrimary) "Phone" else v.getDescription(context) ?: "SD card", v.isPrimary))
        }
        if (out.isEmpty()) out.add(VolumeInfo(File(Environment.getExternalStorageDirectory().path), "Phone", true))
        return out.distinctBy { it.dir.path }
    }

    fun volumeDirs(context: Context): List<File> = volumes(context).map { it.dir }

    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private fun directoryOf(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory
        runCatching {
            val p = StorageVolume::class.java.getMethod("getPath").invoke(v) as? String
            if (p != null) return File(p)
        }
        v.uuid?.let { File("/storage/$it").takeIf { f -> f.exists() }?.let { f -> return f } }
        return if (v.isPrimary) Environment.getExternalStorageDirectory() else null
    }
}
