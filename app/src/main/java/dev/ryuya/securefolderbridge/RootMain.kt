package dev.ryuya.securefolderbridge

import android.os.IBinder
import android.os.Process
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
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

        fun available(): Boolean {
            val profileUnlocked = unlocked.invoke(users, 150) as? Boolean ?: return false
            return profileUnlocked && !isLocked(0) && !isLocked(150)
        }
    }

    private fun native(helper: String, operation: String): String {
        val child = ProcessBuilder(helper, operation).redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText().trim()
        check(child.waitFor() == 0) { output.ifBlank { "Mount operation failed" } }
        return output
    }

    @JvmStatic
    fun main(arguments: Array<String>) {
        var keeper: java.lang.Process? = null
        var helper: String? = null
        var exitCode = 0
        try {
            check(Process.myUid() == 0) { "Root is required" }
            check(arguments.size == 2) { "Expected helper and operation" }
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
                "readonly", "readwrite" -> {
                    check(gate.available()) { "Unlock the phone and Secure Folder first" }
                    native(helper, "status") // Creates and validates the root-owned control directory.
                    RandomAccessFile("$PRIVATE/session.lock", "rw").use { lockFile ->
                        val lock = lockFile.channel.tryLock() ?: error("Another connection is active")
                        lock.use {
                            val stopped = AtomicBoolean(false)
                            val input = Thread({
                                try {
                                    // Closing the controlling app also closes this pipe.
                                    System.`in`.bufferedReader().readLine()
                                } finally {
                                    stopped.set(true)
                                }
                            }, "BridgeControl").apply { isDaemon = true }
                            input.start()
                            check(gate.available()) { "Secure Folder became locked" }
                            val child = ProcessBuilder(helper, if (operation == "readonly") "keep-mount-ro" else "keep-mount-rw")
                                .redirectErrorStream(true).start()
                            keeper = child
                            val first = child.inputStream.bufferedReader().readLine()
                            check(first == "READ_ONLY" || first == "READ_WRITE") { first ?: "Mount helper stopped" }
                            println("CONNECTED")
                            System.out.flush()
                            while (!stopped.get()) {
                                Thread.sleep(750)
                                if (!gate.available()) {
                                    println("LOCKED")
                                    break
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
            val child = keeper
            if (child != null) {
                try {
                    child.outputStream.close()
                    check(child.waitFor(5, TimeUnit.SECONDS) && child.exitValue() == 0) {
                        "Mount helper did not detach cleanly"
                    }
                } catch (error: Throwable) {
                    System.err.println("ERROR: Could not detach folder: ${error.message}")
                    exitCode = 1
                }
                println("OFF")
                System.out.flush()
            }
        }
        kotlin.system.exitProcess(exitCode)
    }
}
