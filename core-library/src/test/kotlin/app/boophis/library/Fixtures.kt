// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import java.io.File

object Fixtures {
    /** The fake storage volume: Movies/, Shows/ plus ignored DCIM/ and Download/. */
    val volume: File by lazy {
        val url = Fixtures::class.java.classLoader!!.getResource("fixtures/volume")
        checkNotNull(url) { "fixtures/volume missing from test resources" }
        File(url.toURI())
    }

    fun file(rel: String) = File(volume, rel)
}
