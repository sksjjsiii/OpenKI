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
        @Volatile var statusText: String = "در حال شروع..."

        val logBuffer: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val sessionBuffers: ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> = ConcurrentHashMap()
        @Volatile private var instance: OpencodeService? = null

        fun sendInput(input: String, session: String = MAIN_SESSION) { instance?.writeToSession(session, input) }
        fun getStatus(): String = statusText
        fun createSession(): String = instance?.createNewSession() ?: MAIN_SESSION
        fun closeSession(id: String) { instance?.closeSessionInternal(id) }
        fun listSessions(): List<String> = instance?.sessions?.keys()?.toList()?.sorted() ?: listOf(MAIN_SESSION)
    }

    data class ShellSession(
        val id: String,
        var process: Process? = null,
        var stdin: OutputStream? = null,
        val running: AtomicBoolean = AtomicBoolean(false),
        val ready: AtomicBoolean = AtomicBoolean(false)
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
        if (text != lastEmittedStatus) { lastEmittedStatus = text; emit("[وضعیت] $text", MAIN_SESSION) }
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
        } catch (e: Exception) { emit("[هشدار] کپی ${src.name} ناموفق: ${e.message}"); false }
    }

    private fun prepareProotBinaries(): Boolean {
        try {
            val srcDir = nativeLibDir()
            val dstDir = prootBinDir().apply { mkdirs() }
            try { emit("[وضعیت] nativeLibraryDir: ${srcDir.absolutePath} -> " + (srcDir.list()?.joinToString(", ") ?: "(خالی)")) } catch (_: Exception) {}
            val prootSrc = File(srcDir, "libproot.so"); val loaderSrc = File(srcDir, "libproot-loader.so")
            if (!prootSrc.exists() || !loaderSrc.exists()) { emit("[خطا] libproot.so یا libproot-loader.so پیدا نشد."); return false }
            try { prootSrc.setExecutable(true, false) } catch (_: Exception) {}
            val tallocSrc = when {
                File(srcDir, "libtalloc.so.2").exists() -> File(srcDir, "libtalloc.so.2")
                File(srcDir, "libtalloc.so").exists() -> File(srcDir, "libtalloc.so")
                else -> srcDir.listFiles()?.firstOrNull { it.name.startsWith("libtalloc") }
            }
            if (tallocSrc != null && tallocSrc.exists()) {
                copyLibIfNeeded(tallocSrc, File(dstDir, "libtalloc.so.2"))
                if (tallocSrc.name != "libtalloc.so.2") copyLibIfNeeded(tallocSrc, File(dstDir, tallocSrc.name))
            } else emit("[هشدار] libtalloc پیدا نشد.")
            val shmemSrc = File(srcDir, "libandroid-shmem.so").takeIf { it.exists() } ?: srcDir.listFiles()?.firstOrNull { it.name.contains("android-shmem") }
            if (shmemSrc != null) copyLibIfNeeded(shmemSrc, File(dstDir, "libandroid-shmem.so"))
            emit("[وضعیت] وابستگی‌های proot آماده: " + (dstDir.list()?.joinToString(", ") ?: ""))
            return true
        } catch (e: Exception) { emit("[خطا] آماده‌سازی proot ناموفق: ${e.message}"); return false }
    }

    private fun bootstrapAndRun() {
        try {
            val rootfs = rootfsDir()
            bootstrapped = isBootstrappedOnDisk()
            setStatus("در حال آماده‌سازی سرویس و proot...")
            if (!prepareProotBinaries()) { setStatus("خطا: آماده‌سازی proot ناموفق بود."); serviceRunning.set(false); return }
            if (!rootfs.exists() || rootfs.list()?.isEmpty() != false) {
                setStatus("در حال استخراج فایل‌های محیط Debian (اجرا فقط در بار اول)...")
                extractRootfs(rootfs)
            } else {
                emit("[وضعیت] محیط Debian موجود است (بدون حذف داده‌ها).")
            }
            ensureResolvConf(rootfs)
            ensureProotFixes(rootfs)
            ensureMainSession()
        } catch (e: Exception) {
            setStatus("خطا در آماده‌سازی: ${e.message}")
            emit("[خطا] ${e.stackTraceToString()}")
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
            val wrapper = "#!/bin/sh\norig=\"\"\nfor p in /bin/$1 /usr/bin/$1; do if [ -x \"\$p\" ] && [ \"\$p\" != \"$0\" ]; then orig=\$p; break; fi; done\nif [ -n \"\$orig\" ]; then \"\$orig\" \"$@\" 2>/dev/null || true; else /bin/busybox $1 \"$@\" 2>/dev/null || true; fi\nexit 0\n"
            // simpler dedicated wrappers
            writeExecutable(File(localBin, "chown"), "#!/bin/sh\n/bin/chown \"\$@\" 2>/dev/null || /usr/bin/chown \"\$@\" 2>/dev/null || true\nexit 0\n")
            writeExecutable(File(localBin, "chgrp"), "#!/bin/sh\n/bin/chgrp \"\$@\" 2>/dev/null || /usr/bin/chgrp \"\$@\" 2>/dev/null || true\nexit 0\n")
            // also place in /usr/sbin-like paths if needed? PATH includes /usr/local/sbin/bin first in our env? we set PATH to include /usr/local/sbin:/usr/local/bin first
            val localSbin = File(rootfs, "usr/local/sbin"); localSbin.mkdirs()
            writeExecutable(File(localSbin, "chown"), "#!/bin/sh\n/bin/chown \"\$@\" 2>/dev/null || /usr/bin/chown \"\$@\" 2>/dev/null || true\nexit 0\n")
            writeExecutable(File(localSbin, "chgrp"), "#!/bin/sh\n/bin/chgrp \"\$@\" 2>/dev/null || /usr/bin/chgrp \"\$@\" 2>/dev/null || true\nexit 0\n")

            val aptDir = File(rootfs, "etc/apt/apt.conf.d"); aptDir.mkdirs()
            File(aptDir, "99andoco").writeText("APT::Sandbox::User \"root\";\nAcquire::Retries \"4\";\nDpkg::Options { \"--force-confdef\"; \"--force-confold\"; \"--force-overwrite\"; };\nDir::Cache::archives \"/var/cache/apt/archives\";\n")
            val dpkgDir = File(rootfs, "etc/dpkg/dpkg.cfg.d"); dpkgDir.mkdirs()
            File(dpkgDir, "99andoco").writeText("force-confdef\nforce-confold\nforce-overwrite\n")

            // marker so we know fixes applied
            try { File(rootfs, "var/lib").mkdirs(); File(rootfs, "var/lib/.andoco_fixes").writeText("1") } catch (_: Exception) {}
        } catch (e: Exception) { emit("[هشدار] اصلاحات proot ناموفق: ${e.message}") }
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
        if (p != null) { emit("[وضعیت] فایل rootfs یافت شد: $p"); return assets.open(p) }
        try {
            val zip = java.util.zip.ZipFile(applicationInfo.sourceDir)
            for (n in listOf("assets/flutter_assets/assets/debian-rootfs.tar.xz","assets/flutter_assets/debian-rootfs.tar.xz","assets/debian-rootfs.tar.xz")) {
                val entry = zip.getEntry(n) ?: continue
                emit("[وضعیت] خواندن rootfs از APK: $n")
                return object : java.io.FilterInputStream(zip.getInputStream(entry)) { override fun close(){ try{super.close()}catch(_:Exception){}; try{zip.close()}catch(_:Exception){} } }
            }
            zip.close()
        } catch (e: Exception) { emit("[هشدار] خواندن از APK ناموفق: ${e.message}") }
        throw java.io.FileNotFoundException("debian-rootfs.tar.xz پیدا نشد.")
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
                    } catch (e: Exception) { emit("[هشدار] استخراج ${entry.name}: ${e.message}") }
                    count++; if (count % 250 == 0) setStatus("در حال استخراج محیط Debian... ($count مورد)")
                    entry = tar.nextTarEntry
                }
            }
        }
        emit("[وضعیت] استخراج کامل شد ($count مورد).")
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
        // bind app external storage dirs? not necessary inside proot path mapping except via /sdcard if bind-mounted; keep simple
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
            "/bin/bash", "-il"))
        return args
    }

    private fun startProcessWithFallback(cmdArgs: List<String>, env: Map<String, String>): Process {
        try { val pb = ProcessBuilder(cmdArgs); pb.redirectErrorStream(true); pb.environment().putAll(env); return pb.start() } catch (e: Exception) { emit("[هشدار] اجرای مستقیم ناموفق: ${e.message}؛ تلاش با linker64...") }
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
        emit("[وضعیت] در حال ایجاد نشست $id...", id)
        thread(name = "andoco-shell-$id") { runSessionLoop(session, isMain = (id == MAIN_SESSION)) }
        return id
    }

    private fun runSessionLoop(session: ShellSession, isMain: Boolean) {
        while (serviceRunning.get() && (isMain || sessions.containsKey(session.id))) {
            session.running.set(false); session.ready.set(false)
            if (isMain) { serverReady = false; bootstrapped = isBootstrappedOnDisk() }
            if (isMain) { if (!bootstrapped) setStatus("در حال آماده‌سازی اولیه محیط...") else setStatus("در حال اجرای محیط تعاملی...") }
            val p = try { startProcessWithFallback(buildProotShellArgs(), prootEnv()) } catch (e: Exception) {
                emit("[خطا] اجرای proot ناموفق: ${e.message}", session.id); Thread.sleep(3000); if (!isMain) break else continue
            }
            synchronized(session) { session.process = p; session.stdin = p.outputStream; session.running.set(true) }
            val reader = thread(name = "andoco-reader-${session.id}") {
                try { BufferedReader(InputStreamReader(p.inputStream)).use { br -> var line: String?; while (br.readLine().also { line = it } != null) {
                    val l = line!!; emit(l, session.id)
                    if (l.contains("__ANDOCO_SHELL_READY__")) session.ready.set(true)
                    if (isMain && l.contains("__ANDOCO_BOOTSTRAP_DONE__")) { bootstrapped = true; setStatus("آماده‌سازی/بررسی محیط کامل شد.") }
                    if (isMain && l.contains("__ANDOCO_BOOTSTRAP_FAILED__")) setStatus("آماده‌سازی ناموفق بود (جزئیات در ترمینال).")
                    if (isMain && l.contains("__ANDOCO_STARTING_SERVER__")) setStatus("در حال اجرای سرور OpenCode...")
                } } } catch (_: Exception) {}
            }
            thread(name = "andoco-init-${session.id}") {
                try { for (i in 0..120){ if(!serviceRunning.get()) return@thread; if(session.ready.get()) break; Thread.sleep(100) }
                    injectInit(session.id, isMain)
                } catch (_: Exception) {}
            }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            try { reader.join(500) } catch (_: Exception) {}
            synchronized(session) { if (session.process == p) { session.process = null; session.stdin = null }; session.running.set(false) }
            if (isMain) serverReady = false
            if (!serviceRunning.get()) break
            if (!isMain) { emit("[وضعیت] نشست ${session.id} بسته شد (کد $code).", session.id); break }
            setStatus("نشست اصلی پایان یافت (کد $code). راه‌اندازی مجدد...")
            Thread.sleep(1500)
        }
        if (!isMain) sessions.remove(session.id)
    }

    private fun injectInit(sessionId: String, isMain: Boolean) {
        // Always set sane prompt/env and mark ready
        val init = "stty sane 2>/dev/null || true\nexport PS1='andoco:\\w# '\nexport LANG=C.UTF-8\nexport LC_ALL=C.UTF-8\nexport TERM=xterm-256color\nexport COLUMNS=120\nexport LINES=40\necho __ANDOCO_SHELL_READY__\n"
        writeToSession(sessionId, init)

        if (isMain) {
            // In-place repair/bootstrap without wiping rootfs or apt cache/data
            val repair = """
( set +e
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
echo '[وضعیت] بررسی و رفع خودکار محیط (بدون حذف داده‌ها)...'
mkdir -p /etc/apt/apt.conf.d /var/lib/dpkg/updates /var/lib/dpkg/info /var/lib/dpkg/triggers /tmp /root/.npm-andoco || true
printf 'APT::Sandbox::User "root";\nAcquire::Retries "4";\nDpkg::Options { "--force-confdef"; "--force-confold"; "--force-overwrite"; };\n' > /etc/apt/apt.conf.d/99andoco || true
printf 'force-confdef\nforce-confold\nforce-overwrite\n' > /etc/dpkg/dpkg.cfg.d/99andoco || true
# chown/chgrp wrappers to avoid perl unpack ownership errors in proot
for d in /usr/local/sbin /usr/local/bin; do mkdir -p "${'$'}d"; cat > "${'$'}d/chown" <<'CH'
#!/bin/sh
/bin/chown "${'$'}@" 2>/dev/null || /usr/bin/chown "${'$'}@" 2>/dev/null || true
exit 0
CH
cat > "${'$'}d/chgrp" <<'CH'
#!/bin/sh
/bin/chgrp "${'$'}@" 2>/dev/null || /usr/bin/chgrp "${'$'}@" 2>/dev/null || true
exit 0
CH
chmod +x "${'$'}d/chown" "${'$'}d/chgrp" || true; done
export PATH=/usr/local/sbin:/usr/local/bin:${'$'}PATH

fix_dpkg() {
  echo '[وضعیت] dpkg --configure -a ...'
  dpkg --configure -a --force-confdef --force-confold --force-overwrite || true
  echo '[وضعیت] apt-get -f install ...'
  apt-get -o APT::Sandbox::User=root -y -f install -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite || true
}

try_apt_update() { apt-get -o APT::Sandbox::User=root update || apt-get update; }

try_install_base() {
  echo '[وضعیت] نصب/تکمیل بسته‌های پایه...'
  apt-get -o APT::Sandbox::User=root install -y --no-install-recommends \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite \
    ca-certificates curl git xz-utils tar nano || \
  apt-get -o APT::Sandbox::User=root install -y --no-install-recommends \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite \
    ca-certificates curl xz-utils tar || true
}

ensure_node() {
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    echo "[وضعیت] Node.js آماده: ${'$'}(node -v 2>/dev/null || echo '?') npm ${'$'}(npm -v 2>/dev/null || echo '?')"
    return 0
  fi
  echo '[وضعیت] تلاش نصب nodejs/npm از مخازن...'
  apt-get -o APT::Sandbox::User=root install -y --no-install-recommends \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite \
    nodejs npm || true
  fix_dpkg
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    echo "[وضعیت] Node.js آماده: ${'$'}(node -v 2>/dev/null || echo '?') npm ${'$'}(npm -v 2>/dev/null || echo '?')"
    return 0
  fi
  echo '[وضعیت] نصب Node.js باینری رسمی (fallback، بدون حذف داده‌ها)...'
  mkdir -p /usr/local /tmp
  NODE_VER="v20.18.0"
  NODE_TGZ="node-${'$'}{NODE_VER}-linux-arm64.tar.xz"
  URL="https://nodejs.org/dist/${'$'}{NODE_VER}/${'$'}{NODE_TGZ}"
  rm -f "/tmp/${'$'}{NODE_TGZ}" || true
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 4 --retry-delay 2 "${'$'}URL" -o "/tmp/${'$'}{NODE_TGZ}" || return 1
  elif command -v wget >/dev/null 2>&1; then
    wget -O "/tmp/${'$'}{NODE_TGZ}" "${'$'}URL" || return 1
  else
    echo '[خطا] curl/wget موجود نیست.'; return 1
  fi
  tar -xJf "/tmp/${'$'}{NODE_TGZ}" -C /usr/local --strip-components=1 || tar -xf "/tmp/${'$'}{NODE_TGZ}" -C /usr/local --strip-components=1 || return 1
  rm -f "/tmp/${'$'}{NODE_TGZ}" || true
  hash -r 2>/dev/null || true
  command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1
}

install_opencode() {
  if command -v opencode >/dev/null 2>&1; then
    echo "[وضعیت] OpenCode آماده است: ${'$'}(opencode --version 2>/dev/null || echo installed)"
    return 0
  fi
  if ! command -v npm >/dev/null 2>&1; then echo '[خطا] npm موجود نیست.'; return 1; fi
  export npm_config_cache="/root/.npm-andoco"
  export npm_config_prefix="/usr/local"
  export npm_config_fund=false
  export npm_config_audit=false
  mkdir -p /root/.npm-andoco /usr/local/bin /usr/local/lib || true
  # clean stale tmp to avoid ENOENT rename issues
  rm -rf /root/.npm-andoco/_cacache/tmp /root/.npm-andoco/_cacache/index-v5 2>/dev/null || true
  npm cache verify >/dev/null 2>&1 || npm cache clean --force >/dev/null 2>&1 || true
  echo '[وضعیت] نصب OpenCode (npm)...'
  npm install -g opencode-termux --no-audit --no-fund --prefer-online --cache /root/.npm-andoco && hash -r 2>/dev/null || true
  if ! command -v opencode >/dev/null 2>&1; then
    echo '[وضعیت] تلاش مجدد نصب OpenCode (روش جایگزین)...'
    rm -rf /root/.npm-andoco/_cacache/tmp 2>/dev/null || true
    npm install -g opencode-ai --no-audit --no-fund --cache /root/.npm-andoco || true
    hash -r 2>/dev/null || true
  fi
  if ! command -v opencode >/dev/null 2>&1; then
    # last resort: fetch tarball and install
    echo '[وضعیت] تلاش نصب از tarball...'
    (cd /tmp && rm -f opencode-termux-*.tgz && npm pack opencode-termux >/tmp/ocpack.log 2>&1 && PKG=$(ls opencode-termux-*.tgz 2>/dev/null | head -1) && [ -n "${'$'}PKG" ] && npm install -g "/tmp/${'$'}PKG" --no-audit --no-fund --cache /root/.npm-andoco) || true
    hash -r 2>/dev/null || true
  fi
  command -v opencode >/dev/null 2>&1
}

# If already healthy, skip heavy work
if command -v opencode >/dev/null 2>&1 && command -v node >/dev/null 2>&1; then
  echo "[وضعیت] محیط آماده است: node ${'$'}(node -v 2>/dev/null) , opencode موجود."
  touch /.bootstrapped 2>/dev/null || true
  echo __ANDOCO_BOOTSTRAP_DONE__
  exit 0
fi

echo '[وضعیت] بررسی شبکه...'
(getent hosts registry.npmjs.org >/dev/null 2>&1 || getent hosts deb.debian.org >/dev/null 2>&1 || ping -c1 1.1.1.1 >/dev/null 2>&1 || echo '[هشدار] شبکه/DNS در دسترس نیست.')

# repair dpkg state first (important for previous perl failures)
fix_dpkg
try_apt_update || true
try_install_base || true
fix_dpkg
ensure_node || true
fix_dpkg
ensure_node || true
install_opencode || true

if command -v opencode >/dev/null 2>&1; then
  touch /.bootstrapped 2>/dev/null || true
  echo __ANDOCO_BOOTSTRAP_DONE__
  exit 0
elif command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
  echo '[هشدار] OpenCode نصب نشد اما Node/npm آماده است. می‌توانید بعداً از تب ابزارها دوباره تلاش کنید.'
  touch /.bootstrapped 2>/dev/null || true
  echo __ANDOCO_BOOTSTRAP_DONE__
  exit 0
else
  echo '[خطا] آماده‌سازی کامل نشد. اینترنت/مخازن را بررسی کنید و دوباره تلاش کنید.'
  echo __ANDOCO_BOOTSTRAP_FAILED__
  exit 1
fi
) || echo __ANDOCO_BOOTSTRAP_FAILED__
""".trimIndent() + "\n"
            // Send as a clean single block (no stray control chars)
            writeToSession(sessionId, repair.replace("\r", ""))
            for (i in 0..2400) { if (!serviceRunning.get()) return; if (File(rootfsDir(), ".bootstrapped").exists() && commandLikelyReady()) break; Thread.sleep(1000) }
        }

        if (isMain) {
            bootstrapped = isBootstrappedOnDisk()
            if (serviceRunning.get()) {
                val startServer = "echo '[وضعیت] تلاش برای اجرای سرور OpenCode...'\necho __ANDOCO_STARTING_SERVER__\nif command -v opencode >/dev/null 2>&1; then opencode web --port 4096 --hostname 127.0.0.1 --print-logs --log-level INFO --pure; echo '[وضعیت] سرور OpenCode متوقف شد.'; else echo '[هشدار] دستور opencode یافت نشد. از تب ابزارها نصب/رفع را انجام دهید.'; fi\n"
                writeToSession(sessionId, startServer)
            }
        } else {
            // secondary terminal: just a ready interactive shell prompt
            emit("[وضعیت] نشست ${sessionId} آماده است.", sessionId)
        }
    }

    private fun commandLikelyReady(): Boolean { return File(rootfsDir(), "usr/local/bin/opencode").exists() || File(rootfsDir(), "usr/bin/opencode").exists() || File(rootfsDir(), ".bootstrapped").exists() }

    private fun waitForPortAndNotify() {
        for (i in 0 until 900) {
            if (!serviceRunning.get()) return
            try { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", 4096), 500) }; serverReady = true; setStatus("سرور OpenCode آماده: http://127.0.0.1:4096"); return } catch (_: Exception) { Thread.sleep(1000) }
        }
    }

    private fun writeToSession(sessionId: String, input: String) {
        try {
            val s = sessions[sessionId] ?: sessions[MAIN_SESSION] ?: return
            val os = synchronized(s) { s.stdin } ?: return
            os.write(input.toByteArray())
            os.flush()
        } catch (e: Exception) { emit("[وضعیت] ارسال ورودی ناموفق: ${e.message}", sessionId) }
    }

    fun closeSessionInternal(id: String) {
        if (id == MAIN_SESSION) return
        val s = sessions.remove(id) ?: return
        try { synchronized(s){ s.stdin?.close() } } catch (_: Exception) {}
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
