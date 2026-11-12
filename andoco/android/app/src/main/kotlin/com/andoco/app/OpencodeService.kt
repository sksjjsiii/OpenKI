package com.andoco.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.system.Os
import androidx.core.app.NotificationCompat
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OpencodeService : Service() {

    companion object {
        const val ACTION_START = "com.andoco.app.action.START"
        const val ACTION_STOP = "com.andoco.app.action.STOP"
        const val ACTION_RELOAD = "com.andoco.app.action.RELOAD"
        const val ACTION_LOG = "com.andoco.app.action.LOG"
        const val EXTRA_LINE = "line"
        const val EXTRA_SESSION = "session"
        const val CHANNEL_ID = "andoco_service"
        const val NOTIF_ID = 1
        const val MAIN_SESSION = "main"

        @Volatile var serverReady: Boolean = false
        @Volatile var bootstrapped: Boolean = false
        @Volatile var statusText: String = "Starting..."

        val logBuffer: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val sessionBuffers: ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> = ConcurrentHashMap()
        @Volatile private var instance: OpencodeService? = null

        fun sendInput(input: String, session: String = MAIN_SESSION) { instance?.writeToSession(session, input) }
        fun getStatus(): String = statusText
        fun createSession(): String = instance?.createNewSession() ?: MAIN_SESSION
        fun closeSession(id: String) { instance?.closeSessionInternal(id) }
        fun listSessions(): List<String> = instance?.sessions?.keys()?.toList()?.sorted() ?: listOf(MAIN_SESSION)

        fun getSessionIo(id: String): SessionIo? {
            val inst = instance ?: return null
            val s = inst.sessions[id] ?: return null
            val tin = s.termIn ?: return null
            val tout = s.toProcOut ?: return null
            return SessionIo(tin, tout)
        }
        fun ensureSessionAndGetIo(id: String): SessionIo? {
            val inst = instance ?: return null
            if (!inst.sessions.containsKey(id)) inst.createNewSession(id)
            for (i in 0..50) {
                val io = getSessionIo(id)
                if (io != null) return io
                try { Thread.sleep(100) } catch (_: Exception) {}
            }
            return getSessionIo(id)
        }
    }

    data class SessionIo(val input: InputStream, val output: OutputStream)

    data class ShellSession(
        val id: String,
        var process: Process? = null,
        var stdin: OutputStream? = null,
        val running: AtomicBoolean = AtomicBoolean(false),
        val ready: AtomicBoolean = AtomicBoolean(false),
        var termIn: PipedInputStream? = null,
        var termOut: PipedOutputStream? = null,
        var toProcOut: PipedOutputStream? = null
    )

    private val sessions = ConcurrentHashMap<String, ShellSession>()
    private val sessionCounter = AtomicInteger(1)
    private var bootstrapThread: Thread? = null
    private val serviceRunning = AtomicBoolean(false)
    @Volatile private var mainAutoStarted = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this; createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            ACTION_RELOAD -> { thread { restartSession(MAIN_SESSION) } }
            else -> {
                startForeground(NOTIF_ID, buildNotification(statusText))
                if (serviceRunning.compareAndSet(false, true)) {
                    bootstrapThread = thread(name = "andoco-bootstrap") { bootstrapAndRun() }
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Andoco Service", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Andoco").setContentText(text.take(120))
        .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()

    private fun updateNotification(text: String) {
        try { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.notify(NOTIF_ID, buildNotification(text)) } catch (_: Exception) {}
    }

    private fun emit(line: String, session: String = MAIN_SESSION) {
        logBuffer.add(line); while (logBuffer.size > 10000) logBuffer.poll()
        val sb = sessionBuffers.getOrPut(session) { ConcurrentLinkedQueue() }
        sb.add(line); while (sb.size > 10000) sb.poll()
        val i = Intent(ACTION_LOG).setPackage(packageName)
        i.putExtra(EXTRA_LINE, line)
        i.putExtra(EXTRA_SESSION, session)
        sendBroadcast(i)
    }

    private var lastEmittedStatus: String = ""
    private fun setStatus(text: String) {
        statusText = text
        if (text != lastEmittedStatus) { lastEmittedStatus = text; emit("[status] $text", MAIN_SESSION) }
        updateNotification(text)
    }

    private fun rootfsDir(): File = File(filesDir, "rootfs")
    private fun nativeLibDir(): File = File(applicationInfo.nativeLibraryDir)
    private fun prootBinDir(): File = File(filesDir, "proot-bin")
    private fun prootPath(): String = File(nativeLibDir(), "libproot.so").absolutePath
    private fun loaderPath(): String = File(nativeLibDir(), "libproot-loader.so").absolutePath
    private fun isBootstrappedOnDisk(): Boolean = File(rootfsDir(), ".bootstrapped").exists()

    private fun copyLibIfNeeded(src: File, dst: File): Boolean {
        return try {
            if (!src.exists()) return false
            if (!dst.exists() || dst.length() != src.length()) {
                src.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs) } }
            }
            try { dst.setReadable(true, false) } catch (_: Exception) {}
            try { dst.setWritable(true, true) } catch (_: Exception) {}
            try { dst.setExecutable(true, false) } catch (_: Exception) {}
            true
        } catch (e: Exception) { emit("[warn] Failed to copy ${src.name}: ${e.message}"); false }
    }

    private fun prepareProotBinaries(): Boolean {
        try {
            val srcDir = nativeLibDir()
            val dstDir = prootBinDir().apply { mkdirs() }
            try { emit("[status] nativeLibraryDir: ${srcDir.absolutePath} -> " + (srcDir.list()?.joinToString(", ") ?: "(empty)")) } catch (_: Exception) {}
            val prootSrc = File(srcDir, "libproot.so"); val loaderSrc = File(srcDir, "libproot-loader.so")
            if (!prootSrc.exists() || !loaderSrc.exists()) { emit("[error] libproot.so or libproot-loader.so not found."); return false }
            try { prootSrc.setExecutable(true, false) } catch (_: Exception) {}
            val tallocSrc = when {
                File(srcDir, "libtalloc.so.2").exists() -> File(srcDir, "libtalloc.so.2")
                File(srcDir, "libtalloc.so").exists() -> File(srcDir, "libtalloc.so")
                else -> srcDir.listFiles()?.firstOrNull { it.name.startsWith("libtalloc") }
            }
            if (tallocSrc != null && tallocSrc.exists()) {
                copyLibIfNeeded(tallocSrc, File(dstDir, "libtalloc.so.2"))
                if (tallocSrc.name != "libtalloc.so.2") copyLibIfNeeded(tallocSrc, File(dstDir, tallocSrc.name))
            } else emit("[warn] libtalloc not found.")
            val shmemSrc = File(srcDir, "libandroid-shmem.so").takeIf { it.exists() } ?: srcDir.listFiles()?.firstOrNull { it.name.contains("android-shmem") }
            if (shmemSrc != null) copyLibIfNeeded(shmemSrc, File(dstDir, "libandroid-shmem.so"))
            emit("[status] proot dependencies ready: " + (dstDir.list()?.joinToString(", ") ?: ""))
            return true
        } catch (e: Exception) { emit("[error] proot preparation failed: ${e.message}"); return false }
    }

    private fun bootstrapAndRun() {
        try {
            val rootfs = rootfsDir()
            bootstrapped = isBootstrappedOnDisk()
            setStatus("Preparing service and proot...")
            if (!prepareProotBinaries()) { setStatus("Error: proot preparation failed."); serviceRunning.set(false); return }
            if (!rootfs.exists() || rootfs.list()?.isEmpty() != false) {
                setStatus("Extracting Debian rootfs (first run only)...")
                extractRootfs(rootfs)
            } else {
                emit("[status] Debian rootfs already present (no data deleted).")
            }
            ensureResolvConf(rootfs)
            ensureProotFixes(rootfs)
            ensureMainSession()
        } catch (e: Exception) {
            setStatus("Preparation error: ${e.message}")
            emit("[error] ${e.stackTraceToString()}")
            serviceRunning.set(false)
        }
    }

    private fun ensureResolvConf(rootfs: File) {
        try { val etc = File(rootfs, "etc"); etc.mkdirs(); val r = File(etc, "resolv.conf"); if (!r.exists() || r.length()==0L) r.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") } catch (_: Exception) {}
    }

    private fun writeExecutable(file: File, content: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            try { Os.chmod(file.path, 0b111101101) } catch (_: Exception) { try { file.setExecutable(true, false) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun ensureProotFixes(rootfs: File) {
        try {
            val localBin = File(rootfs, "usr/local/bin"); localBin.mkdirs()
            val localSbin = File(rootfs, "usr/local/sbin"); localSbin.mkdirs()
            val noop = "#!/bin/sh\nexit 0\n"
            for (d in listOf(localBin, localSbin)) {
                for (name in listOf("chown", "chgrp", "dpkg-statoverride", "install-info")) {
                    writeExecutable(File(d, name), noop)
                }
            }
            // also place noop wrappers in common system paths if writable (helps dpkg maintainer scripts)
            for (path in listOf("bin/chown", "bin/chgrp", "usr/bin/chown", "usr/bin/chgrp", "usr/sbin/dpkg-statoverride", "usr/bin/dpkg-statoverride", "usr/bin/install-info")) {
                val f = File(rootfs, path)
                try {
                    if (f.exists() || f.parentFile?.exists() == true) {
                        // replace with noop wrapper to avoid ownership errors in proot
                        val backup = File(f.parentFile, f.name + ".andoco-real")
                        if (f.exists() && !backup.exists()) {
                            try { f.renameTo(backup) } catch (_: Exception) {}
                        }
                        writeExecutable(f, noop)
                    }
                } catch (_: Exception) {}
            }

            val aptDir = File(rootfs, "etc/apt/apt.conf.d"); aptDir.mkdirs()
            File(aptDir, "99andoco").writeText(
                "APT::Sandbox::User \"root\";\n" +
                "Acquire::Retries \"6\";\n" +
                "Acquire::http::Timeout \"20\";\n" +
                "Acquire::https::Timeout \"20\";\n" +
                "Dpkg::Options { \"--force-confdef\"; \"--force-confold\"; \"--force-overwrite\"; \"--force-unsafe-io\"; \"--force-depends\"; };\n" +
                "Dir::Cache::archives \"/var/cache/apt/archives\";\n"
            )
            val dpkgDir = File(rootfs, "etc/dpkg/dpkg.cfg.d"); dpkgDir.mkdirs()
            File(dpkgDir, "99andoco").writeText("force-confdef\nforce-confold\nforce-overwrite\nforce-unsafe-io\nforce-depends\nno-debsig\n")
            try { File(rootfs, "tmp").mkdirs(); Os.chmod(File(rootfs, "tmp").path, 0b111111111) } catch (_: Exception) {}
            try { File(rootfs, "var/tmp").mkdirs(); Os.chmod(File(rootfs, "var/tmp").path, 0b111111111) } catch (_: Exception) {}
            try { File(rootfs, "usr/bin/perlthanks.dpkg-new").apply { parentFile?.mkdirs(); createNewFile() } } catch (_: Exception) {}
            try { File(rootfs, "usr/bin/perlbug.dpkg-new").apply { parentFile?.mkdirs(); createNewFile() } } catch (_: Exception) {}
            try { File(rootfs, "var/lib").mkdirs(); File(rootfs, "var/lib/.andoco_fixes").writeText("3") } catch (_: Exception) {}
        } catch (e: Exception) { emit("[warn] proot fixes failed: ${e.message}") }
    }

    private fun findRootfsAssetPath(): String? {
        val candidates = listOf("flutter_assets/assets/debian-rootfs.tar.xz","flutter_assets/debian-rootfs.tar.xz","assets/debian-rootfs.tar.xz","debian-rootfs.tar.xz")
        for (name in candidates) { try { assets.open(name).close(); return name } catch (_: Exception) {} }
        try { val roots = listOf("", "flutter_assets", "flutter_assets/assets", "assets")
            for (r in roots) { val list = try { assets.list(r)?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
                for (f in list) if (f=="debian-rootfs.tar.xz") { val p = if (r.isEmpty()) f else "$r/$f"; try { assets.open(p).close(); return p } catch (_: Exception) {} } } } catch (_: Exception) {}
        return null
    }

    private fun openRootfsStream(): java.io.InputStream {
        val p = findRootfsAssetPath()
        if (p != null) { emit("[status] Rootfs asset found: $p"); return assets.open(p) }
        try {
            val zip = java.util.zip.ZipFile(applicationInfo.sourceDir)
            for (n in listOf("assets/flutter_assets/assets/debian-rootfs.tar.xz","assets/flutter_assets/debian-rootfs.tar.xz","assets/debian-rootfs.tar.xz")) {
                val entry = zip.getEntry(n) ?: continue
                emit("[status] Reading rootfs from APK: $n")
                return object : java.io.FilterInputStream(zip.getInputStream(entry)) { override fun close(){ try{super.close()}catch(_:Exception){}; try{zip.close()}catch(_:Exception){} } }
            }
            zip.close()
        } catch (e: Exception) { emit("[warn] Failed to read from APK: ${e.message}") }
        throw java.io.FileNotFoundException("debian-rootfs.tar.xz not found.")
    }

    private fun extractRootfs(target: File) {
        if (target.exists()) try { target.deleteRecursively() } catch (_: Exception) {}
        target.mkdirs()
        var count = 0
        openRootfsStream().use { assetIn ->
            val xzIn = XZInputStream(assetIn)
            TarArchiveInputStream(xzIn).use { tar ->
                var entry = tar.nextTarEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    val outFile = File(target, entry.name)
                    if (!outFile.canonicalPath.startsWith(target.canonicalPath + File.separator)) { entry = tar.nextTarEntry; continue }
                    try {
                        when {
                            entry.isDirectory -> { outFile.mkdirs(); try { Os.chmod(outFile.path, entry.mode) } catch (_: Exception) {} }
                            entry.isSymbolicLink -> { outFile.parentFile?.mkdirs(); try { val l=java.nio.file.Paths.get(outFile.path); java.nio.file.Files.deleteIfExists(l); java.nio.file.Files.createSymbolicLink(l, java.nio.file.Paths.get(entry.linkName)) } catch (_: Exception) {} }
                            entry.isLink -> { outFile.parentFile?.mkdirs(); try { val l=java.nio.file.Paths.get(outFile.path); val t=java.nio.file.Paths.get(target.path, entry.linkName); java.nio.file.Files.deleteIfExists(l); java.nio.file.Files.createLink(l,t) } catch (_: Exception) {} }
                            else -> {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos -> var remaining=entry.size; while(remaining>0){ val n=tar.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt()); if(n<=0) break; fos.write(buffer,0,n); remaining-=n } }
                                try { Os.chmod(outFile.path, entry.mode) } catch (_: Exception) { if (entry.mode and 0b001001001 != 0) try { outFile.setExecutable(true,false) } catch(_:Exception){} }
                            }
                        }
                    } catch (e: Exception) { emit("[warn] Extract ${entry.name}: ${e.message}") }
                    count++; if (count % 250 == 0) setStatus("Extracting Debian rootfs... ($count items)")
                    entry = tar.nextTarEntry
                }
            }
        }
        emit("[status] Extraction complete ($count items).")
    }

    private fun prootEnv(): Map<String, String> {
        val depDir = prootBinDir().absolutePath; val nativeDir = nativeLibDir().absolutePath
        val tmpDir = File(filesDir, "tmp").apply { mkdirs() }.absolutePath
        val existingLd = System.getenv("LD_LIBRARY_PATH")
        val ld = listOf(depDir, nativeDir, existingLd ?: "").filter { it.isNotBlank() }.joinToString(":")
        return mapOf(
            "PROOT_LOADER" to loaderPath(),
            "PROOT_TMP_DIR" to tmpDir,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_L2S_DIR" to File(filesDir, "proot-l2s").apply { mkdirs() }.absolutePath,
            "LD_LIBRARY_PATH" to ld
        )
    }

    private fun buildProotShellArgs(cols: Int = 120, rows: Int = 40): List<String> {
        val root = rootfsDir().absolutePath
        val args = mutableListOf(prootPath(), "--link2symlink", "-0", "-r", root, "-b", "/dev", "-b", "/proc", "-b", "/sys")
        val hostResolv = File("/etc/resolv.conf")
        if (hostResolv.exists() && hostResolv.canRead()) args.addAll(listOf("-b", "/etc/resolv.conf:/etc/resolv.conf"))
        // bind /storage and /sdcard if available for user access
        for (p in listOf("/storage", "/sdcard")) { try { if (File(p).exists()) args.addAll(listOf("-b", "$p:$p")) } catch (_: Exception) {} }
        args.addAll(listOf("-w", "/root", "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "COLUMNS=$cols",
            "LINES=$rows",
            "/bin/bash", "-c", "set +m 2>/dev/null || true; exec /bin/bash --login -i 2>/dev/null || exec /bin/bash -i"))
        return args
    }

    private fun startProcessWithFallback(cmdArgs: List<String>, env: Map<String, String>): Process {
        try { val pb = ProcessBuilder(cmdArgs); pb.redirectErrorStream(true); pb.environment().putAll(env); return pb.start() } catch (e: Exception) { emit("[warn] Direct exec failed: ${e.message}; trying linker64...") }
        val linker = when { File("/system/bin/linker64").exists() -> "/system/bin/linker64"; File("/system/bin/linker").exists() -> "/system/bin/linker"; else -> null }
        if (linker != null) { try { val pb = ProcessBuilder(listOf(linker)+cmdArgs); pb.redirectErrorStream(true); pb.environment().putAll(env); return pb.start() } catch (_: Exception) {} }
        val pb = ProcessBuilder(cmdArgs); pb.redirectErrorStream(true); pb.environment().putAll(env); return pb.start()
    }

    private fun ensureMainSession() {
        if (!sessions.containsKey(MAIN_SESSION)) createNewSession(MAIN_SESSION)
        thread(name = "andoco-port-watch") { waitForPortAndNotify() }
    }

    fun createNewSession(forceId: String? = null): String {
        val id = forceId ?: "s${sessionCounter.getAndIncrement()}"
        if (sessions.containsKey(id)) return id
        val session = ShellSession(id)
        sessions[id] = session
        sessionBuffers.getOrPut(id) { ConcurrentLinkedQueue() }
        emit("[status] Creating session $id...", id)
        thread(name = "andoco-shell-$id") { runSessionLoop(session, isMain = (id == MAIN_SESSION)) }
        return id
    }

    private fun runSessionLoop(session: ShellSession, isMain: Boolean) {
        while (serviceRunning.get() && (isMain || sessions.containsKey(session.id))) {
            session.running.set(false); session.ready.set(false)
            if (isMain) { serverReady = false; bootstrapped = isBootstrappedOnDisk() }
            if (isMain) { if (!bootstrapped) setStatus("Running initial environment setup...") else setStatus("Starting interactive environment...") }
            val p = try { startProcessWithFallback(buildProotShellArgs(), prootEnv()) } catch (e: Exception) {
                emit("[error] Failed to start proot: ${e.message}", session.id); Thread.sleep(3000); if (!isMain) break else continue
            }
            val termOut = PipedOutputStream()
            val termIn = try { PipedInputStream(termOut, 65536) } catch (_: Exception) { PipedInputStream(termOut) }
            val toProcIn = try { PipedInputStream(65536) } catch (_: Exception) { PipedInputStream() }
            val toProcOut = PipedOutputStream(toProcIn)
            synchronized(session) {
                session.process = p; session.stdin = p.outputStream; session.running.set(true)
                session.termOut = termOut; session.termIn = termIn; session.toProcOut = toProcOut
            }
            val reader = thread(name = "andoco-reader-${session.id}") {
                val buf = ByteArray(8192)
                val lineBuf = StringBuilder()
                var lastWasCR = false
                try {
                    p.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            try {
                                for (i in 0 until n) {
                                    val b = buf[i].toInt() and 0xFF
                                    if (b == 0x0A) {
                                        if (!lastWasCR) termOut.write(0x0D)
                                        termOut.write(0x0A)
                                        lastWasCR = false
                                    } else if (b == 0x0D) {
                                        termOut.write(0x0D)
                                        lastWasCR = true
                                    } else {
                                        termOut.write(b)
                                        lastWasCR = false
                                    }
                                }
                                termOut.flush()
                            } catch (_: Exception) {}
                            val chunk = String(buf, 0, n, Charsets.UTF_8)
                            var startIdx = 0
                            for (i in chunk.indices) {
                                val c = chunk[i]
                                if (c == '\n') {
                                    val seg = if (i > startIdx) chunk.substring(startIdx, i) else ""
                                    lineBuf.append(seg.replace("\r", ""))
                                    val l = lineBuf.toString()
                                    lineBuf.setLength(0)
                                    emit(l, session.id)
                                    if (l.contains("__ANDOCO_SHELL_READY__")) session.ready.set(true)
                                    if (isMain && l.contains("__ANDOCO_BOOTSTRAP_DONE__")) { bootstrapped = true; setStatus("Environment setup/check complete.") }
                                    if (isMain && l.contains("__ANDOCO_BOOTSTRAP_FAILED__")) setStatus("Setup failed (see terminal for details).")
                                    if (isMain && l.contains("__ANDOCO_STARTING_SERVER__")) setStatus("Starting OpenCode server...")
                                    startIdx = i + 1
                                }
                            }
                            if (startIdx < chunk.length) lineBuf.append(chunk.substring(startIdx))
                        }
                    }
                } catch (_: Exception) {}
                try { if (lineBuf.isNotEmpty()) { val l = lineBuf.toString().replace("\r", ""); emit(l, session.id) } } catch (_: Exception) {}
                try { termOut.close() } catch (_: Exception) {}
            }
            val writer = thread(name = "andoco-writer-${session.id}") {
                val wbuf = ByteArray(4096)
                try {
                    p.outputStream.use { os ->
                        while (true) {
                            val n = try { toProcIn.read(wbuf) } catch (_: Exception) { -1 }
                            if (n <= 0) break
                            try { os.write(wbuf, 0, n); os.flush() } catch (_: Exception) { break }
                        }
                    }
                } catch (_: Exception) {}
                try { toProcIn.close() } catch (_: Exception) {}
            }
            thread(name = "andoco-init-${session.id}") {
                try { for (i in 0..120){ if(!serviceRunning.get()) return@thread; if(session.ready.get()) break; Thread.sleep(100) }
                    injectInit(session.id, isMain)
                } catch (_: Exception) {}
            }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            try { toProcOut.close() } catch (_: Exception) {}
            try { reader.join(800) } catch (_: Exception) {}
            try { writer.join(300) } catch (_: Exception) {}
            synchronized(session) {
                if (session.process == p) { session.process = null; session.stdin = null }
                session.running.set(false)
                try { session.termIn?.close() } catch (_: Exception) {}
                try { session.termOut?.close() } catch (_: Exception) {}
                try { session.toProcOut?.close() } catch (_: Exception) {}
                session.termIn = null; session.termOut = null; session.toProcOut = null
            }
            if (isMain) serverReady = false
            if (!serviceRunning.get()) break
            if (!isMain) { emit("[status] Session ${session.id} exited (code $code).", session.id); break }
            setStatus("Main session exited (code $code). Restarting...")
            Thread.sleep(1500)
        }
        if (!isMain) sessions.remove(session.id)
    }

    private fun injectInit(sessionId: String, isMain: Boolean) {
        val init = "stty sane 2>/dev/null || true\nexport PS1='andoco:\\w# '\nexport LANG=C.UTF-8\nexport LC_ALL=C.UTF-8\nexport TERM=xterm-256color\nexport COLUMNS=120\nexport LINES=40\necho __ANDOCO_SHELL_READY__\n"
        writeToSession(sessionId, init)

        if (isMain) {
            val repair = """
( set +e
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8 LC_ALL=C.UTF-8
set +m 2>/dev/null || true
echo '[status] Checking and repairing environment (without deleting data)...'
mkdir -p /etc/apt/apt.conf.d /etc/dpkg/dpkg.cfg.d /var/lib/dpkg/updates /var/lib/dpkg/info /var/lib/dpkg/triggers /tmp /var/tmp /root/.npm-cache || true
chmod 1777 /tmp /var/tmp 2>/dev/null || true
printf 'APT::Sandbox::User "root";\nAcquire::Retries "6";\nAcquire::http::Timeout "20";\nAcquire::https::Timeout "20";\nDpkg::Options { "--force-confdef"; "--force-confold"; "--force-overwrite"; "--force-unsafe-io"; };\n' > /etc/apt/apt.conf.d/99andoco || true
printf 'force-confdef\nforce-confold\nforce-overwrite\nforce-unsafe-io\nno-debsig\n' > /etc/dpkg/dpkg.cfg.d/99andoco || true

for d in /usr/local/sbin /usr/local/bin; do
  mkdir -p "${'$'}d"
  for b in chown chgrp dpkg-statoverride install-info; do
    printf '%s\n' '#!/bin/sh' 'exit 0' > "${'$'}d/${'$'}b"
    chmod +x "${'$'}d/${'$'}b" 2>/dev/null || true
  done
done
export PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH
hash -r 2>/dev/null || true

log(){ echo "[status] ${'$'}*"; }
warn(){ echo "[warn] ${'$'}*"; }

fix_broken_perl(){
  if dpkg -s perl >/dev/null 2>&1 && dpkg -s liberror-perl >/dev/null 2>&1 && dpkg -s git >/dev/null 2>&1; then return 0; fi
  log 'Attempting to repair broken packages (perl/liberror-perl/git) without deleting data...'
  mkdir -p /tmp/andoco-fix-debs
  ( cd /tmp/andoco-fix-debs && apt-get -o APT::Sandbox::User=root download perl perl-base perl-modules-5.36 liberror-perl git >/tmp/andoco-perl-dl.log 2>&1 || true )
  for deb in /tmp/andoco-fix-debs/*.deb /var/cache/apt/archives/perl*.deb /var/cache/apt/archives/liberror-perl*.deb /var/cache/apt/archives/git*.deb; do
    [ -f "${'$'}deb" ] || continue
    PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH dpkg -i --force-confdef --force-confold --force-overwrite --force-depends --force-unsafe-io --no-triggers "${'$'}deb" >/tmp/andoco-perl-dpkg.log 2>&1 || true
  done
  for deb in /tmp/andoco-fix-debs/*.deb /var/cache/apt/archives/perl*.deb /var/cache/apt/archives/liberror-perl*.deb; do
    [ -f "${'$'}deb" ] || continue
    dpkg-deb -x "${'$'}deb" / >/dev/null 2>&1 || true
  done
  rm -f /usr/bin/perlthanks.dpkg-new /usr/bin/*.dpkg-new 2>/dev/null || true
  PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH dpkg --configure -a --force-confdef --force-confold --force-overwrite --force-unsafe-io >/tmp/andoco-perl-cfg.log 2>&1 || true
  return 0
}

dpkg_force_configure(){
  log 'Running dpkg --configure -a (proot-safe)...'
  PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH dpkg --configure -a --force-confdef --force-confold --force-overwrite --force-unsafe-io || \
  PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH dpkg --configure --pending --force-confdef --force-confold --force-overwrite --force-unsafe-io || true
  fix_broken_perl || true
  for pkg in liberror-perl git perl perl-base perl-modules-5.36; do
    dpkg -s "${'$'}pkg" >/dev/null 2>&1 && dpkg --configure --force-confdef --force-confold --force-overwrite "${'$'}pkg" >/dev/null 2>&1 || true
  done
}

apt_fix(){
  log 'Repairing apt/dpkg dependencies...'
  PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH apt-get -o APT::Sandbox::User=root -y -f install \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite -o Dpkg::Options::=--force-unsafe-io || true
  dpkg_force_configure
}

try_apt_update(){ apt-get -o APT::Sandbox::User=root update || apt-get update || true; }

try_install_base(){
  log 'Installing/verifying base packages (if available)...'
  PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH apt-get -o APT::Sandbox::User=root install -y --no-install-recommends \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite -o Dpkg::Options::=--force-unsafe-io \
    ca-certificates curl git xz-utils tar nano >/tmp/andoco-apt-base.log 2>&1 || \
  PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH apt-get -o APT::Sandbox::User=root install -y --no-install-recommends \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite -o Dpkg::Options::=--force-unsafe-io \
    ca-certificates curl xz-utils tar || true
  dpkg_force_configure
}

ensure_node(){
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    log "Node.js ready: ${'$'}(node -v 2>/dev/null || echo '?') npm ${'$'}(npm -v 2>/dev/null || echo '?')"; return 0
  fi
  log 'Trying to install nodejs/npm from apt...'
  PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH apt-get -o APT::Sandbox::User=root install -y --no-install-recommends \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite -o Dpkg::Options::=--force-unsafe-io nodejs npm || true
  apt_fix
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    log "Node.js ready: ${'$'}(node -v 2>/dev/null || echo '?') npm ${'$'}(npm -v 2>/dev/null || echo '?')"; return 0
  fi
  log 'Installing official Node.js binary (fallback)...'
  NODE_VER="v20.18.0"; NODE_TGZ="node-${'$'}{NODE_VER}-linux-arm64.tar.xz"; URL="https://nodejs.org/dist/${'$'}{NODE_VER}/${'$'}{NODE_TGZ}"
  rm -f "/tmp/${'$'}{NODE_TGZ}" || true
  if command -v curl >/dev/null 2>&1; then curl -fL --retry 5 --retry-delay 2 "${'$'}URL" -o "/tmp/${'$'}{NODE_TGZ}" || true
  elif command -v wget >/dev/null 2>&1; then wget -O "/tmp/${'$'}{NODE_TGZ}" "${'$'}URL" || true; fi
  if [ -f "/tmp/${'$'}{NODE_TGZ}" ]; then
    tar -xJf "/tmp/${'$'}{NODE_TGZ}" -C /usr/local --strip-components=1 || tar -xf "/tmp/${'$'}{NODE_TGZ}" -C /usr/local --strip-components=1 || true
    rm -f "/tmp/${'$'}{NODE_TGZ}" || true
  fi
  hash -r 2>/dev/null || true
  command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1
}

find_opencode_candidates(){
  local npmroot
  npmroot=$(npm root -g 2>/dev/null || echo "")
  local cands=()
  for cand in \
    "${'$'}npmroot/opencode-termux/bin/opencode" \
    "${'$'}npmroot/opencode-termux/bin/opencode.js" \
    "${'$'}npmroot/opencode-termux/dist/index.js" \
    "${'$'}npmroot/opencode-ai/bin/opencode" \
    "${'$'}npmroot/opencode-ai/bin/opencode.js" \
    "${'$'}npmroot/opencode-ai/dist/index.js" \
    /usr/local/lib/node_modules/opencode-termux/bin/opencode \
    /usr/local/lib/node_modules/opencode-termux/bin/opencode.js \
    /usr/local/lib/node_modules/opencode-termux/dist/index.js \
    /usr/local/lib/node_modules/opencode-ai/bin/opencode \
    /usr/local/lib/node_modules/opencode-ai/bin/opencode.js \
    /usr/local/lib/node_modules/opencode-ai/dist/index.js \
    /root/.opencode-local/node_modules/opencode-termux/bin/opencode \
    /root/.opencode-local/node_modules/opencode-termux/bin/opencode.js \
    /root/.opencode-local/node_modules/opencode-ai/bin/opencode \
    /root/.opencode-local/node_modules/opencode-ai/bin/opencode.js; do
    [ -f "${'$'}cand" ] && echo "${'$'}cand"
  done
  if [ -n "${'$'}npmroot" ]; then
    find "${'$'}npmroot"/opencode-termux "${'$'}npmroot"/opencode-ai -type f \( -name 'opencode' -o -name 'opencode.js' -o -name 'index.js' -o -name 'cli.js' \) 2>/dev/null || true
  fi
  find /usr/local/lib/node_modules/opencode-termux /usr/local/lib/node_modules/opencode-ai /root/.opencode-local/node_modules/opencode-termux /root/.opencode-local/node_modules/opencode-ai -type f \( -name 'opencode' -o -name 'opencode.js' -o -name 'index.js' -o -name 'cli.js' \) 2>/dev/null || true
}

find_opencode_entry(){
  find_opencode_candidates | awk '!seen[$0]++' | head -1
}

ensure_opencode_runnable(){
  local nodebin=""
  if command -v node >/dev/null 2>&1; then nodebin=$(command -v node); elif [ -x /usr/local/bin/node ]; then nodebin=/usr/local/bin/node; fi
  [ -n "${'$'}nodebin" ] || return 1

  # if existing opencode works, keep it
  if command -v opencode >/dev/null 2>&1; then
    local out rc
    out=$(opencode --version 2>&1); rc=${'$'}?
    if [ ${'$'}rc -eq 0 ]; then return 0; fi
    warn "opencode command not runnable (rc=${'$'}rc); rebuilding wrapper..."
  fi

  # try to create a working wrapper from candidates
  local target
  while IFS= read -r target; do
    [ -n "${'$'}target" ] || continue
    [ -f "${'$'}target" ] || continue
    # avoid obviously wrong huge files? not necessary; try
    cat > /usr/local/bin/opencode <<WRAP
#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
exec "${'$'}nodebin" "${'$'}target" "$@"
WRAP
    chmod +x /usr/local/bin/opencode 2>/dev/null || true
    hash -r 2>/dev/null || true
    if opencode --version >/dev/null 2>&1; then
      log "opencode wrapper ready: ${'$'}target"
      return 0
    fi
  done <<CANDS
$(find_opencode_candidates | awk '!seen[$0]++')
CANDS

  # fallback: if an opencode file exists but has bad shebang, patch it
  if [ -f /usr/local/bin/opencode ]; then
    if head -c 2 /usr/local/bin/opencode 2>/dev/null | grep -q '#!'; then
      sed -i '1s|^#!/usr/bin/env node|#!'"${'$'}nodebin"'|' /usr/local/bin/opencode 2>/dev/null || true
      sed -i '1s|^#!.*node.*|#!'"${'$'}nodebin"'|' /usr/local/bin/opencode 2>/dev/null || true
      chmod +x /usr/local/bin/opencode 2>/dev/null || true
      hash -r 2>/dev/null || true
      opencode --version >/dev/null 2>&1 && return 0
    fi
  fi
  return 1
}

install_opencode(){
  if ensure_opencode_runnable; then
    log "OpenCode ready: ${'$'}(opencode --version 2>/dev/null || echo installed)"; return 0
  fi
  if ! command -v npm >/dev/null 2>&1; then warn 'npm is not available.'; return 1; fi
  export npm_config_cache="/root/.npm-cache"
  export npm_config_prefix="/usr/local"
  export npm_config_fund=false
  export npm_config_audit=false
  export npm_config_progress=false
  export npm_config_fetch_retries=5
  export npm_config_fetch_retry_mintimeout=20000
  export npm_config_fetch_retry_maxtimeout=120000
  mkdir -p /root/.npm-cache /usr/local/bin /usr/local/lib || true
  rm -rf /root/.npm-cache/_cacache/tmp 2>/dev/null || true
  npm cache verify >/dev/null 2>&1 || npm cache clean --force >/dev/null 2>&1 || true

  try_npm_install_pkg(){
    local pkg="${'$'}1"
    log "Installing OpenCode via npm (${'$'}pkg)..."
    rm -rf /root/.npm-cache/_cacache/tmp 2>/dev/null || true
    npm install -g "${'$'}pkg" --no-audit --no-fund --prefer-online --cache /root/.npm-cache 2>&1 && return 0
    rm -rf /root/.npm-cache/_cacache/tmp /root/.npm-cache/_cacache/index-v5 2>/dev/null || true
    npm install -g "${'$'}pkg" --no-audit --no-fund --prefer-online --cache /root/.npm-cache 2>&1 && return 0
    local altcache="/tmp/npm-cache-fresh"
    rm -rf "${'$'}altcache" 2>/dev/null || true; mkdir -p "${'$'}altcache" || true
    npm install -g "${'$'}pkg" --no-audit --no-fund --prefer-online --cache "${'$'}altcache" 2>&1 && return 0
    return 1
  }

  try_npm_install_pkg opencode-termux || true
  hash -r 2>/dev/null || true
  ensure_opencode_runnable || true
  if ! ensure_opencode_runnable; then
    try_npm_install_pkg opencode-ai || true
    hash -r 2>/dev/null || true
    ensure_opencode_runnable || true
  fi
  if ! ensure_opencode_runnable; then
    log 'Trying tarball install (npm pack/install)...'
    for pkg in opencode-termux opencode-ai; do
      ( cd /tmp || exit 1
        rm -f ./${'$'}pkg-*.tgz /tmp/ocpack.log 2>/dev/null || true
        rm -rf /root/.npm-cache/_cacache/tmp 2>/dev/null || true
        npm pack "${'$'}pkg" --cache /root/.npm-cache >/tmp/ocpack.log 2>&1 || npm pack "${'$'}pkg" --cache /tmp/npm-cache-fresh >/tmp/ocpack.log 2>&1 || exit 1
        PKG=$(ls ${'$'}pkg-*.tgz 2>/dev/null | head -1)
        [ -n "${'$'}PKG" ] || exit 1
        npm install -g "/tmp/${'$'}PKG" --no-audit --no-fund --cache /root/.npm-cache 2>&1 || npm install -g "/tmp/${'$'}PKG" --no-audit --no-fund --cache /tmp/npm-cache-fresh 2>&1 || exit 1
      ) && hash -r 2>/dev/null || true
      ensure_opencode_runnable && break || true
    done
  fi
  if ! ensure_opencode_runnable; then
    log 'Trying local install and wrapper...'
    mkdir -p /root/.opencode-local /usr/local/bin || true
    for pkg in opencode-termux opencode-ai; do
      ( cd /root/.opencode-local && rm -rf /root/.npm-cache/_cacache/tmp 2>/dev/null || true && npm init -y >/dev/null 2>&1 || true && npm install "${'$'}pkg" --no-audit --no-fund --cache /root/.npm-cache ) >/tmp/oclocal.log 2>&1 || \
      ( cd /root/.opencode-local && npm install "${'$'}pkg" --no-audit --no-fund --cache /tmp/npm-cache-fresh ) >/tmp/oclocal.log 2>&1 || true
      ensure_opencode_runnable && break || true
    done
  fi
  ensure_opencode_runnable
}

if command -v node >/dev/null 2>&1; then
  ensure_opencode_runnable >/dev/null 2>&1 || true
  if command -v opencode >/dev/null 2>&1 && opencode --version >/dev/null 2>&1; then
    log "Environment ready: node ${'$'}(node -v 2>/dev/null), opencode present."
    touch /.bootstrapped 2>/dev/null || true
    echo __ANDOCO_BOOTSTRAP_DONE__
    exit 0
  fi
fi

log 'Checking network...'
(getent hosts registry.npmjs.org >/dev/null 2>&1 || getent hosts deb.debian.org >/dev/null 2>&1 || getent hosts nodejs.org >/dev/null 2>&1 || ping -c1 1.1.1.1 >/dev/null 2>&1 || warn 'Network/DNS may be unavailable.')

dpkg_force_configure
apt_fix
try_apt_update
try_install_base
apt_fix
ensure_node || true
apt_fix
ensure_node || true
install_opencode || true

if command -v node >/dev/null 2>&1; then ensure_opencode_runnable >/dev/null 2>&1 || true; fi
if command -v opencode >/dev/null 2>&1 && opencode --version >/dev/null 2>&1; then
  touch /.bootstrapped 2>/dev/null || true
  echo __ANDOCO_BOOTSTRAP_DONE__
  exit 0
elif command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
  warn 'OpenCode is not installed yet but Node/npm are ready. You can retry from Tools.'
  touch /.bootstrapped 2>/dev/null || true
  echo __ANDOCO_BOOTSTRAP_DONE__
  exit 0
else
  echo '[error] Setup did not complete. Check network/repositories and retry.'
  echo __ANDOCO_BOOTSTRAP_FAILED__
  exit 1
fi
) || echo __ANDOCO_BOOTSTRAP_FAILED__
""".trimIndent() + "\n"
            val safe = repair.replace("\r", "").replace("\u0003", "")
            writeToSession(sessionId, "cat > /tmp/andoco_bootstrap.sh <<'__ANDOCO_SH__'\n")
            for (chunk in safe.chunked(3000)) writeToSession(sessionId, chunk)
            writeToSession(sessionId, "\n__ANDOCO_SH__\n/bin/bash /tmp/andoco_bootstrap.sh; echo __ANDOCO_INIT_EXIT__:${'$'}?\n")
            for (i in 0..2400) { if (!serviceRunning.get()) return; if (File(rootfsDir(), ".bootstrapped").exists() && commandLikelyReady()) break; Thread.sleep(1000) }
        }

        if (isMain) {
            bootstrapped = isBootstrappedOnDisk()
            if (serviceRunning.get()) {
                                val startServer = "echo '[status] Starting OpenCode server...'\necho __ANDOCO_STARTING_SERVER__\nexport PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\nexport HOME=/root\nNODEBIN=\$(command -v node 2>/dev/null || echo /usr/local/bin/node)\nNPMROOT=\$(npm root -g 2>/dev/null || echo '')\nis_listening(){ (command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ':4096') || ( (echo >/dev/tcp/127.0.0.1/4096) >/dev/null 2>&1 ); }\ntry_run(){\n  echo \"[status] Launching: \$*\"\n  \"\$@\" &\n  pid=\$!\n  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25; do\n    if is_listening; then echo '[status] OpenCode port 4096 is listening.'; wait \$pid; return \$?; fi\n    if ! kill -0 \$pid 2>/dev/null; then wait \$pid; return \$?; fi\n    sleep 1\n  done\n  if is_listening || kill -0 \$pid 2>/dev/null; then wait \$pid; return \$?; fi\n  wait \$pid; return \$?\n}\nrc=127\nif command -v opencode >/dev/null 2>&1; then\n  try_run opencode web --port 4096 --hostname 127.0.0.1 --print-logs --log-level INFO --pure; rc=\$?\nfi\nif [ \$rc -ne 0 ] && ! is_listening; then\n  echo '[warn] opencode command launch failed or not ready; trying node entrypoints...'\n  for c in \"\$NPMROOT/opencode-termux/bin/opencode\" \"\$NPMROOT/opencode-termux/bin/opencode.js\" \"\$NPMROOT/opencode-termux/dist/index.js\" \"\$NPMROOT/opencode-ai/bin/opencode\" \"\$NPMROOT/opencode-ai/bin/opencode.js\" \"\$NPMROOT/opencode-ai/dist/index.js\" /usr/local/lib/node_modules/opencode-termux/bin/opencode /usr/local/lib/node_modules/opencode-termux/bin/opencode.js /usr/local/lib/node_modules/opencode-termux/dist/index.js /usr/local/lib/node_modules/opencode-ai/bin/opencode /usr/local/lib/node_modules/opencode-ai/bin/opencode.js /usr/local/lib/node_modules/opencode-ai/dist/index.js /root/.opencode-local/node_modules/opencode-termux/bin/opencode /root/.opencode-local/node_modules/opencode-ai/bin/opencode; do\n    [ -f \"\$c\" ] || continue\n    try_run \"\$NODEBIN\" \"\$c\" web --port 4096 --hostname 127.0.0.1 --print-logs --log-level INFO --pure; rc=\$?\n    if [ \$rc -eq 0 ] || is_listening; then break; fi\n  done\nfi\nif [ \$rc -ne 0 ] && ! is_listening; then\n  c=\$(find \"\$NPMROOT\" /usr/local/lib/node_modules /root/.opencode-local/node_modules -type f \\( -path '*opencode*bin/*' -o -name 'cli.js' -o -name 'index.js' \\) 2>/dev/null | grep opencode | head -1)\n  if [ -n \"\$c\" ]; then try_run \"\$NODEBIN\" \"\$c\" web --port 4096 --hostname 127.0.0.1 --print-logs --log-level INFO --pure; rc=\$?; fi\nfi\nif [ \$rc -eq 0 ] || is_listening; then echo '[status] OpenCode server stopped.'; else echo \"[error] Failed to start OpenCode server (code \$rc).\"; echo \"[status] Diagnostics: node=\$(\"\$NODEBIN\" -v 2>&1 || echo missing)\"; echo '[status] opencode candidates:'; find \"\$NPMROOT\" /usr/local/lib/node_modules /root/.opencode-local -maxdepth 5 -type f 2>/dev/null | grep opencode | head -20; fi\n"
                writeToSession(sessionId, startServer)
            }
        } else {
            emit("[status] Session ${sessionId} is ready.", sessionId)
        }
    }

    private fun commandLikelyReady(): Boolean { return File(rootfsDir(), "usr/local/bin/opencode").exists() || File(rootfsDir(), "usr/bin/opencode").exists() || File(rootfsDir(), ".bootstrapped").exists() }

    private fun waitForPortAndNotify() {
        for (i in 0 until 900) {
            if (!serviceRunning.get()) return
            try { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", 4096), 500) }; serverReady = true; setStatus("OpenCode server ready: http://127.0.0.1:4096"); return } catch (_: Exception) { Thread.sleep(1000) }
        }
    }

    private fun writeToSession(sessionId: String, input: String) {
        try {
            val s = sessions[sessionId] ?: sessions[MAIN_SESSION] ?: return
            val os = synchronized(s) { s.stdin } ?: return
            os.write(input.toByteArray())
            os.flush()
        } catch (e: Exception) { emit("[status] Failed to send input: ${e.message}", sessionId) }
    }

    fun closeSessionInternal(id: String) {
        if (id == MAIN_SESSION) return
        val s = sessions.remove(id) ?: return
        try { synchronized(s){ s.stdin?.close(); s.toProcOut?.close(); s.termOut?.close(); s.termIn?.close() } } catch (_: Exception) {}
        try { s.process?.destroy() } catch (_: Exception) {}
        try { s.process?.destroyForcibly() } catch (_: Exception) {}
    }

    private fun restartSession(id: String) { val s = sessions[id] ?: return; try { s.process?.destroy() } catch (_: Exception) {} }

    private fun stopAll() {
        serviceRunning.set(false); serverReady = false
        for ((_, s) in sessions) { try { synchronized(s){ s.stdin?.close() } } catch (_: Exception) {}; try { s.process?.destroy() } catch (_: Exception) {}; try { s.process?.destroyForcibly() } catch (_: Exception) {} }
        sessions.clear()
    }

    override fun onDestroy() { stopAll(); if (instance == this) instance = null; super.onDestroy() }
}
