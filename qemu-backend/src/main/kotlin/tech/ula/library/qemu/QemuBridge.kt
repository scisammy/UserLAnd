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

    external fun stop()

    external fun connectConsole(socketPath: String): Int

    external fun readConsole(fd: Int, buf: ByteArray, len: Int): Int

    external fun writeConsole(fd: Int, buf: ByteArray, len: Int): Int

    external fun closeConsole(fd: Int)
}
