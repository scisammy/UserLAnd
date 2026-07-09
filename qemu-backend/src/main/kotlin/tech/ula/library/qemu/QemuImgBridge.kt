package tech.ula.library.qemu

object QemuImgBridge {

    init {
        System.loadLibrary("qemu_img_ula")
    }

    external fun create(path: String, sizeBytes: Long): Boolean

    external fun delete(path: String): Boolean
}
