package tech.ula.library.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

class QemuService : LifecycleService() {

    companion object {
        private const val TAG = "QemuService"
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "qemu_session"
        private const val RAM_MB = 1024
        private const val CONNECT_RETRIES = 60
        private const val CONNECT_DELAY_MS = 500L
        private const val READ_BUF_SIZE = 4096

        @Volatile var instance: QemuService? = null
    }

    enum class State { STOPPED, STARTING, RUNNING, ERROR, CORRUPTED }

    private var consoleFd = -1
    private lateinit var rootfs: RootfsManager
    private var fsId: String? = null

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    // Fresh channel per service instance — no stale lines from previous sessions
    val lineChannel = Channel<String>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        instance = this
        rootfs = RootfsManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopQemu()
        instance = null
        _state.value = State.STOPPED
        super.onDestroy()
    }

    fun sendConsoleInput(data: ByteArray) {
        val fd = consoleFd
        if (fd >= 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                QemuBridge.writeConsole(fd, data, data.size)
            }
        }
    }

    suspend fun startQemu(layersPath: String?, qcow2Path: String, fsId: String, expectReady: Boolean): Boolean {
        if (QemuBridge.isRunning()) {
            Log.w(TAG, "QEMU already running")
            return false
        }

        this.fsId = fsId
        _state.value = State.STARTING

        try {
            rootfs.extractAssets()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract kernel assets: ${e.message}")
            _state.value = State.ERROR
            return false
        }

        // expectReady mirrors OciManager.isQcow2Ready() as seen by the caller before this was
        // called: it tells /init whether this QCOW2 is *supposed* to already hold a working
        // rootfs, so a mount/shell-detection failure over there can tell real corruption
        // apart from a disk that's legitimately never been formatted yet. See build-kernel.sh's
        // EXPECT_READY handling and this class's "OCI_ROOTFS_CORRUPT" console watch below.
        val cmdline = "console=ttyAMA0 loglevel=8 oci_auto=1" +
                if (expectReady) " oci_expect_ready=1" else ""
        val mounts = if (layersPath != null) arrayOf("layers:$layersPath") else emptyArray<String>()

        val started = kotlinx.coroutines.withContext(Dispatchers.IO) {
            QemuBridge.start(
                kernelPath     = rootfs.kernelPath,
                initrdPath     = rootfs.initrdPath,
                socketPath     = rootfs.socketPath,
                ramMb          = RAM_MB,
                kernelCmdline  = cmdline,
                virtfsMounts   = mounts,
                diskImagePath  = null,
                rootfsDiskPath = qcow2Path,
            )
        }

        if (!started) {
            Log.e(TAG, "QemuBridge.start() returned false")
            _state.value = State.ERROR
            return false
        }

        consoleFd = kotlinx.coroutines.withContext(Dispatchers.IO) { retryConnect() }
        if (consoleFd < 0) {
            Log.e(TAG, "Failed to connect to console socket")
            _state.value = State.ERROR
            return false
        }

        _state.value = State.RUNNING
        startConsoleReader(consoleFd)
        return true
    }

    fun stopQemu() {
        val fd = consoleFd
        consoleFd = -1
        if (fd >= 0) QemuBridge.closeConsole(fd)
        if (QemuBridge.isRunning()) {
            Thread({ QemuBridge.stop() }, "qemu-stop").apply { isDaemon = true; start() }
        }
        _state.value = State.STOPPED
    }

    private fun startConsoleReader(fd: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val buf = ByteArray(READ_BUF_SIZE)
            val lineBuf = StringBuilder()
            while (isActive && fd >= 0) {
                val n = QemuBridge.readConsole(fd, buf, buf.size)
                if (n <= 0) break
                val text = String(buf, 0, n, Charsets.UTF_8)
                for (ch in text) {
                    if (ch == '\n') {
                        val line = lineBuf.toString().trimEnd('\r')
                        Log.d(TAG, "console: $line")
                        if (line == "OCI_ROOTFS_CORRUPT") {
                            Log.e(TAG, "Guest reported unrepairable rootfs corruption")
                            // Written to disk (not just _state below) so it survives even if
                            // this line is read after QemuSessionManager's caller has already
                            // given up waiting -- see that class's waitForShell() timeout.
                            fsId?.let { OciManager.markCorrupt(applicationContext, it) }
                            _state.value = State.CORRUPTED
                        }
                        lineChannel.trySend(line)
                        lineBuf.clear()
                    } else {
                        lineBuf.append(ch)
                    }
                }
            }
            if (lineBuf.isNotEmpty()) {
                val line = lineBuf.toString().trimEnd('\r')
                Log.d(TAG, "console: $line")
                lineChannel.trySend(line)
            }
            if (_state.value == State.RUNNING) {
                _state.value = State.STOPPED
            }
        }
    }

    private suspend fun retryConnect(): Int {
        // The chardev socket doesn't exist until QEMU's init reaches device realization,
        // typically well under CONNECT_DELAY_MS after the process starts -- but so is a VM
        // that exits abnormally almost immediately after starting. A flat 500ms-per-attempt
        // schedule means, in that failure case, the very first (guaranteed-ENOENT, socket
        // not created yet) attempt is often the *only* one that runs before the VM is already
        // gone, so any console output the guest did manage to produce before dying is never
        // read. Polling fast for the first couple seconds costs nothing on the success path
        // (it just means the eventual real connection happens sooner) and means a
        // fast-crashing VM's console output has a real chance of being captured for
        // diagnosis instead of silently lost.
        repeat(CONNECT_RETRIES) { attempt ->
            val fd = QemuBridge.connectConsole(rootfs.socketPath)
            if (fd >= 0) return fd
            delay(if (attempt < 20) 50L else CONNECT_DELAY_MS)
        }
        return -1
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux Session", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Linux session running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
}
