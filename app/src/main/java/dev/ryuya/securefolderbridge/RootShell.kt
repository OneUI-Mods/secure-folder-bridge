package dev.ryuya.securefolderbridge

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

object RootShell {
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun start(context: Context, operation: String): java.lang.Process {
        require(operation in setOf("check", "status", "readonly", "readwrite", "await-readonly", "await-readwrite"))
        val apk = context.applicationInfo.sourceDir
        val helper = File(context.applicationInfo.nativeLibraryDir, "libfolder_mount.so").absolutePath
        val work = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("work_profile", true)
        val command = "CLASSPATH=${quote(apk)} exec /system/bin/app_process /system/bin " +
            "dev.ryuya.securefolderbridge.RootMain ${quote(helper)} ${quote(operation)} ${if (work) "work" else "main"}"
        return ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).start()
    }

    fun check(context: Context): String {
        val child = start(context, "check")
        return try {
            check(child.waitFor(15, TimeUnit.SECONDS)) { "Root request timed out" }
            val output = child.inputStream.bufferedReader().readText().trim()
            check(child.exitValue() == 0) { output.ifBlank { "Root access was not granted" } }
            output
        } finally {
            child.destroy()
        }
    }
}
