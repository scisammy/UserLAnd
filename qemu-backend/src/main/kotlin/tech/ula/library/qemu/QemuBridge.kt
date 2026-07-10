package tech.ula.library.qemu

object QemuBridge {

    init {
        System.loadLibrary("emul_ula")
    }

    external fun start(
        kernelPath:     String,
        initrdPath:     String,
        socketPath:     String,
        ramMb:          Int,
        kernelCmdline:  String,
        virtfsMounts:   Array<String>,
        diskImagePath:  String?,
        rootfsDiskPath: String?,
    ): Boolean

    external fun isRunning(): Boolean

    // True when a stop() call's 5s grace period expired without the worker thread actually
    // exiting — isRunning() still correctly reports true for the whole time this is true (see
    // qemu_jni.c's stop()), this just distinguishes "running normally" from "wedged past its
    // shutdown deadline" for logging/diagnostics.
    external fun isStuck(): Boolean

    external fun stop()

    external fun connectConsole(socketPath: String): Int

    external fun readConsole(fd: Int, buf: ByteArray, len: Int): Int

    external fun writeConsole(fd: Int, buf: ByteArray, len: Int): Int

    external fun closeConsole(fd: Int)
}
