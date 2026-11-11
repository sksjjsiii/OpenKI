package com.andoco.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.tukaani.xz.XZInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class OpencodeService : Service() {

    companion object {
        const val ACTION_START = "com.andoco.app.action.START"
        const val ACTION_STOP = "com.andoco.app.action.STOP"
        const val ACTION_RELOAD = "com.andoco.app.action.RELOAD"
        const val ACTION_LOG = "com.andoco.app.action.LOG"
        const val EXTRA_LINE = "line"
        const val CHANNEL_ID = "andoco_service"
        const val NOTIF_ID = 1

        @Volatile var serverReady: Boolean = false
        val logBuffer: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    }

    private var process: Process? = null
    private var bootstrapThread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                thread { restartServer() }
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification("Starting..."))
                if (running.compareAndSet(false, true)) {
                    bootstrapThread = thread(name = "andoco-bootstrap") { bootstrapAndRun() }
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Andoco Service", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Andoco")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun emit(line: String) {
        logBuffer.add(line)
        while (logBuffer.size > 2000) logBuffer.poll()
        val i = Intent(ACTION_LOG).setPackage(packageName)
        i.putExtra(EXTRA_LINE, line)
        sendBroadcast(i)
    }

    private fun rootfsDir(): File = File(filesDir, "rootfs")
    private fun prootPath(): String = File(applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
    private fun loaderPath(): String = File(applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath

    private fun bootstrapAndRun() {
        try {
            val rootfs = rootfsDir()
            if (!rootfs.exists() || rootfs.list()?.isEmpty() != false) {
                emit("[andoco] Extracting Debian rootfs...")
                updateNotification("Extracting rootfs...")
                extractRootfs(rootfs)
                emit("[andoco] Rootfs extracted.")
            }
            ensureResolvConf(rootfs)

            val bootstrapped = File(rootfs, ".bootstrapped")
            if (!bootstrapped.exists()) {
                emit("[andoco] Bootstrapping (apt update/install, npm install opencode-termux)...")
                updateNotification("Bootstrapping environment...")
                val bootstrapCmd = """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get update
                    apt-get install -y --no-install-recommends nodejs npm curl git ca-certificates
                    npm install -g opencode-termux
                    touch /.bootstrapped
                """.trimIndent()
                var code = 1
                for (attempt in 1..3) {
                    emit("[andoco] Bootstrap attempt $attempt/3...")
                    code = runProotCommand(bootstrapCmd, streamLogs = true)
                    if (code == 0) break
                    emit("[andoco] Bootstrap attempt $attempt failed (code $code), retrying...")
                    Thread.sleep(3000)
                }
                if (code != 0) {
                    emit("[andoco] Bootstrap failed after retries.")
                    updateNotification("Bootstrap failed (check network)")
                    running.set(false)
                    return
                }
                emit("[andoco] Bootstrap complete.")
            } else {
                emit("[andoco] Already bootstrapped.")
            }

            startServerLoop()
        } catch (e: Exception) {
            emit("[andoco] Error: ${e.message}")
            updateNotification("Error")
            running.set(false)
        }
    }

    private fun ensureResolvConf(rootfs: File) {
        try {
            val etc = File(rootfs, "etc")
            etc.mkdirs()
            val resolv = File(etc, "resolv.conf")
            if (!resolv.exists() || resolv.length() == 0L) {
                resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
        } catch (_: Exception) { }
    }

    private fun extractRootfs(target: File) {
        target.mkdirs()
        assets.open("debian-rootfs.tar.xz").use { assetIn ->
            val xzIn = XZInputStream(assetIn)
            TarArchiveInputStream(xzIn).use { tar ->
                var entry = tar.nextTarEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val outFile = File(target, entry.name)
                    if (!outFile.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                        entry = tar.nextTarEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        outFile.parentFile?.mkdirs()
                        try {
                            val link = java.nio.file.Paths.get(outFile.path)
                            java.nio.file.Files.deleteIfExists(link)
                            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Paths.get(entry.linkName))
                        } catch (_: Exception) { }
                    } else if (entry.isLink) {
                        outFile.parentFile?.mkdirs()
                        try {
                            val link = java.nio.file.Paths.get(outFile.path)
                            val targetPath = java.nio.file.Paths.get(target.path, entry.linkName)
                            java.nio.file.Files.deleteIfExists(link)
                            java.nio.file.Files.createLink(link, targetPath)
                        } catch (_: Exception) {
                            // fallback: copy will be missed but rare
                        }
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var n: Int
                            var remaining = entry.size
                            while (remaining > 0) {
                                n = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (n <= 0) break
                                fos.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        if (entry.mode and 0b001001001 != 0) outFile.setExecutable(true, false)
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun prootEnv(): Map<String, String> = mapOf(
        "PROOT_LOADER" to loaderPath(),
        "PROOT_TMP_DIR" to File(filesDir, "tmp").apply { mkdirs() }.absolutePath,
        "PROOT_NO_SECCOMP" to "1"
    )

    private fun buildProotArgs(innerCmd: String): List<String> {
        val root = rootfsDir().absolutePath
        val args = mutableListOf(
            prootPath(),
            "--link2symlink", "-0", "-r", root,
            "-b", "/dev", "-b", "/proc", "-b", "/sys"
        )
        val hostResolv = File("/etc/resolv.conf")
        if (hostResolv.exists() && hostResolv.canRead()) {
            args.addAll(listOf("-b", "/etc/resolv.conf:/etc/resolv.conf"))
        }
        args.addAll(listOf(
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "/bin/bash", "--login", "-c", innerCmd
        ))
        return args
    }

    private fun runProotCommand(cmd: String, streamLogs: Boolean): Int {
        val pb = ProcessBuilder(buildProotArgs(cmd))
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.putAll(prootEnv())
        val p = pb.start()
        process = p
        if (streamLogs) {
            BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    emit(line!!)
                }
            }
        } else {
            p.inputStream.bufferedReader().forEachLine { emit(it) }
        }
        return p.waitFor()
    }

    private fun startServerLoop() {
        while (running.get()) {
            serverReady = false
            emit("[andoco] Starting OpenCode server...")
            updateNotification("Starting OpenCode...")
            val cmd = "opencode web --port 4096 --hostname 127.0.0.1 --print-logs --log-level INFO --pure"
            val pb = ProcessBuilder(buildProotArgs(cmd))
            pb.redirectErrorStream(true)
            pb.environment().putAll(prootEnv())
            val p = try { pb.start() } catch (e: Exception) {
                emit("[andoco] Failed to start: ${e.message}")
                Thread.sleep(3000)
                continue
            }
            process = p
            thread { waitForPortAndNotify() }
            thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            emit(line!!)
                        }
                    }
                } catch (_: Exception) { }
            }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            emit("[andoco] Server exited with code $code")
            serverReady = false
            if (!running.get()) break
            updateNotification("Restarting...")
            Thread.sleep(2000)
        }
    }

    private fun waitForPortAndNotify() {
        for (i in 0 until 180) {
            if (!running.get()) return
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", 4096), 500)
                }
                serverReady = true
                emit("[andoco] Server listening on http://127.0.0.1:4096")
                updateNotification("OpenCode running")
                return
            } catch (_: Exception) {
                Thread.sleep(1000)
            }
        }
    }

    private fun stopServer() {
        running.set(false)
        serverReady = false
        try { process?.destroy() } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null
    }

    private fun restartServer() {
        try { process?.destroy() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
