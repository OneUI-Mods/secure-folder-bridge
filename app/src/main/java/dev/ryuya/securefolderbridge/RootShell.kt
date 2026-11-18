package dev.ryuya.securefolderbridge

import android.content.Context
import java.io.File

object RootShell {
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun start(context: Context, operation: String): java.lang.Process {
        require(
            operation in
                setOf(
                    "check",
                    "status",
                    "readonly",
                    "readwrite",
                    "await-readonly",
                    "await-readwrite",
                ),
        )
        val apk = context.applicationInfo.sourceDir
        val helper =
            File(context.applicationInfo.nativeLibraryDir, "libfolder_mount.so").absolutePath
        val work =
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("work_profile", true)
        val command =
            "CLASSPATH=${quote(apk)} exec /system/bin/app_process /system/bin " +
                "dev.ryuya.securefolderbridge.RootMain ${quote(helper)} ${quote(operation)} ${if (work) "work" else "main"}"
        return ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).start()
    }

    fun check(context: Context): String {
        val child = start(context, "check")
        val result = CommandOutput.collect(child, 15_000, 16_384)
        val output = result.text.trim()
        check(result.code == 0) { output.ifBlank { "Root access was not granted" } }
        return output
    }
}
