package com.bgmonitoring.shizuku

import rikka.shizuku.Shizuku

/**
 * Wraps Shizuku permission/binding checks.
 */
object ShizukuHelper {
    fun isAvailable(): Boolean = Shizuku.pingBinder()

    fun hasPermission(): Boolean =
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }
}
