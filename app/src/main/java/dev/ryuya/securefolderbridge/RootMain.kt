package dev.ryuya.securefolderbridge

import android.os.IBinder
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only in the explicitly authorized root process, in init's mount namespace. */
object RootMain {
    private const val PRIVATE = "/data/local/tmp/dev.ryuya.securefolderbridge"

    private fun remoteInterface(service: String, interfaceName: String): Any {
        val manager = Class.forName("android.os.ServiceManager")
        val binder = manager.getMethod("getService", String::class.java).invoke(null, service)
            as? IBinder ?: error("Missing system service: $service")
        return Class.forName("$interfaceName\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("Missing interface: $interfaceName")
    }

    private class ProfileGate {
        private val trust = remoteInterface("trust", "android.app.trust.ITrustManager")
        private val users = remoteInterface("user", "android.os.IUserManager")
        private val power = remoteInterface("power", "android.os.IPowerManager")
        private val interactive = Class.forName("android.os.IPowerManager").getMethod("isInteractive")
        private val locked = Class.forName("android.app.trust.ITrustManager").methods.single {
            it.name == "isDeviceLocked" && it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
        }
        private val unlocked = Class.forName("android.os.IUserManager")
            .getMethod("isUserUnlocked", Int::class.javaPrimitiveType)

        private fun isLocked(user: Int): Boolean {
            val arguments = when (locked.parameterCount) {
                1 -> arrayOf<Any>(user)
                2 -> arrayOf<Any>(user, 0)
                else -> error("Unsupported lock service")
            }
            return locked.invoke(trust, *arguments) as? Boolean ?: error("Unknown lock status")
        }

        private fun profileAvailable(user: Int): Boolean {
            val profileUnlocked = unlocked.invoke(users, user) as? Boolean ?: return false
            return profileUnlocked && !isLocked(user)
        }

        fun available(): Boolean = interactive.invoke(power) == true && profileAvailable(150) && !isLocked(0)

        fun workExists(): Boolean = File("/data/media/10").isDirectory

        fun workAvailable(): Boolean = workExists() && profileAvailable(10)
    }

    private fun native(helper: String, operation: String, user: Int = 0): String {
        val child = ProcessBuilder(helper, operation, user.toString()).redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText().trim()
        check(child.waitFor() == 0) { output.ifBlank { "Mount operation failed" } }
        return output
    }

    private fun startKeeper(helper: String, readonly: Boolean, user: Int): java.lang.Process {
        val operation = if (readonly) "keep-mount-ro" else "keep-mount-rw"
        val child = ProcessBuilder(helper, operation, user.toString()).redirectErrorStream(true).start()
        try {
            val first = child.inputStream.bufferedReader().readLine()
            check(first == "READ_ONLY" || first == "READ_WRITE") { first ?: "Mount helper stopped" }
            return child
        } catch (error: Throwable) {
            child.outputStream.close()
            child.waitFor(5, TimeUnit.SECONDS)
            throw error
        }
    }

    private fun stopKeeper(child: java.lang.Process) {
        child.outputStream.close()
        check(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0) {
            "Mount helper did not detach cleanly"
        }
    }

    @JvmStatic
    fun main(arguments: Array<String>) {
        val keepers = linkedMapOf<Int, java.lang.Process>()
        var helper: String? = null
        var exitCode = 0
        try {
            check(Process.myUid() == 0) { "Root is required" }
            check(arguments.size in 2..3) { "Expected helper, operation, and optional work-profile mode" }
            val workProfile = arguments.getOrNull(2) == "work"
            check(arguments.size == 2 || arguments[2] in setOf("work", "main")) { "Unknown profile mode" }
            helper = arguments[0]
            check(File(helper).isFile && File(helper).canExecute()) { "Missing mount helper" }
            val gate = ProfileGate()
            when (val operation = arguments[1]) {
                "check" -> {
                    println(if (gate.available()) "AVAILABLE" else "LOCKED")
                    return
                }
                "status" -> {
                    println(native(helper, "status"))
                    return
                }
                "readonly", "readwrite", "await-readonly", "await-readwrite" -> {
                    val waitForUnlock = operation.startsWith("await-")
                    if (!waitForUnlock) check(gate.available()) { "Unlock the phone and Secure Folder first" }
                    native(helper, "status") // Creates and validates the root-owned control directory.
                    RandomAccessFile("$PRIVATE/session.lock", "rw").use { lockFile ->
                        val lock = lockFile.channel.tryLock() ?: error("Another connection is active")
                        lock.use {
                            val stopped = CountDownLatch(1)
                            val input = Thread({
                                try {
                                    // Closing the controlling app also closes this pipe.
                                    System.`in`.bufferedReader().readLine()
                                } finally {
                                    stopped.countDown()
                                }
                            }, "BridgeControl").apply { isDaemon = true }
                            input.start()
                            if (waitForUnlock && !gate.available()) {
                                println("WAITING")
                                System.out.flush()
                                // No wake lock, alarm, file scan, or mount while waiting.
                                while (stopped.count > 0 && !gate.available()) stopped.await(5, TimeUnit.SECONDS)
                            }
                            if (stopped.count == 0L) return
                            check(gate.available()) { "Secure Folder became locked" }
                            val readonly = operation.endsWith("readonly")
                            keepers[0] = startKeeper(helper, readonly, 0)
                            if (workProfile && gate.workAvailable()) {
                                keepers[10] = startKeeper(helper, readonly, 10)
                                println("WORK_CONNECTED")
                            } else if (workProfile) {
                                println(if (gate.workExists()) "WORK_LOCKED" else "WORK_MISSING")
                            }
                            println("CONNECTED")
                            System.out.flush()
                            while (!stopped.await(750, TimeUnit.MILLISECONDS)) {
                                if (!gate.available()) {
                                    println("LOCKED")
                                    break
                                }
                                check(keepers.values.all { it.isAlive }) { "A mount helper stopped unexpectedly" }
                                if (workProfile) {
                                    val workAvailable = gate.workAvailable()
                                    if (workAvailable && !keepers.containsKey(10)) {
                                        keepers[10] = startKeeper(helper, readonly, 10)
                                        println("WORK_CONNECTED")
                                        System.out.flush()
                                    } else if (!workAvailable && keepers.containsKey(10)) {
                                        val workKeeper = keepers.remove(10)
                                        if (workKeeper != null) stopKeeper(workKeeper)
                                        println(if (gate.workExists()) "WORK_LOCKED" else "WORK_MISSING")
                                        System.out.flush()
                                    }
                                }
                            }
                        }
                    }
                }
                else -> error("Unknown operation")
            }
        } catch (error: Throwable) {
            System.err.println("ERROR: " + (error.cause?.message ?: error.message ?: error.javaClass.simpleName))
            exitCode = 1
        } finally {
            for (child in keepers.values) {
                try {
                    stopKeeper(child)
                } catch (error: Throwable) {
                    System.err.println("ERROR: Could not detach folder: ${error.message}")
                    exitCode = 1
                }
            }
            if (keepers.isNotEmpty()) println("OFF")
            System.out.flush()
        }
        kotlin.system.exitProcess(exitCode)
    }
}
