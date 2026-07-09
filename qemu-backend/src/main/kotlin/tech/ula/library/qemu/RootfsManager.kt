package tech.ula.library.qemu

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream

class RootfsManager(private val ctx: Context) {

    val filesDir: File get() = ctx.filesDir

    val kernelPath: String get() = File(filesDir, "qemu/kernel/Image").absolutePath
    val initrdPath: String get() = File(filesDir, "qemu/kernel/initrd.img").absolutePath
    val socketPath: String get() = File(filesDir, "qemu/console.sock").absolutePath

    fun extractAssets() {
        val kernelDir = File(filesDir, "qemu/kernel")
        kernelDir.mkdirs()

        extractAsset(ctx.assets, "kernel/Image",      kernelPath)
        extractAsset(ctx.assets, "kernel/initrd.img", initrdPath)
    }

    private fun extractAsset(assets: AssetManager, src: String, dst: String) {
        val out = File(dst)
        val assetSize = try { assets.openFd(src).use { it.length } } catch (_: Exception) { -1L }
        if (out.exists() && out.length() == assetSize) return
        assets.open(src).use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
    }
}
