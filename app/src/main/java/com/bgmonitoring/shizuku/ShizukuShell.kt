package com.bgmonitoring.shizuku

import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * Runs shell commands via Shizuku and returns stdout/stderr + exit code.
 * Uses reflection to access newProcess to stay API-version agnostic.
 */
object ShizukuShell {
    data class Result(val stdout: String, val stderr: String, val exitCode: Int)

    fun runCommand(cmd: List<String>): Result {
        if (!Shizuku.pingBinder()) {
            return Result("", "Shizuku binder unavailable", -1)
        }

        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = method.invoke(null, cmd.toTypedArray(), null, null) as ShizukuRemoteProcess

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            Result(stdout = stdout, stderr = stderr, exitCode = exitCode)
        } catch (e: Exception) {
            Log.e("ShizukuShell", "Error executing: ${cmd.joinToString(" ")}", e)
            Result("", e.localizedMessage ?: "error", -1)
        }
    }
}
