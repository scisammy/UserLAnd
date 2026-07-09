package tech.ula.library.qemu

import android.content.Context
import java.io.File

object OciManager {
    private const val MANIFEST_FILE = "manifest.txt"
    private const val REF_FILE      = ".oci_ref"

    const val ROOTFS_SIZE_MB = 8192

    fun layersDir(context: Context, fsId: String): File =
        File(context.filesDir, "qemu_layers_$fsId")

    fun qcow2Path(context: Context, fsId: String): String =
        File(context.filesDir, "qemu_rootfs_$fsId.qcow2").absolutePath

    private fun readyMarker(context: Context, fsId: String): File =
        File(context.filesDir, ".qemu_ready_$fsId")

    // Written when /init's guest-side e2fsck can't repair the OCI rootfs inside the QCOW2
    // (see build-kernel.sh's "OCI_ROOTFS_CORRUPT" handling, watched for by QemuService's
    // console reader). A file, not just in-memory state, for the same reason as the AVF
    // backend's equivalent (ImageManager.corruptMarker): it needs to survive independently
    // of whatever QemuService instance happens to be alive when it's checked.
    private fun corruptMarker(context: Context, fsId: String): File =
        File(context.filesDir, ".qemu_corrupt_$fsId")

    fun isLayersReady(context: Context, fsId: String): Boolean =
        File(layersDir(context, fsId), MANIFEST_FILE).exists()

    fun isQcow2Ready(context: Context, fsId: String): Boolean =
        readyMarker(context, fsId).exists()

    fun markQcow2Ready(context: Context, fsId: String, imageRef: String) =
        readyMarker(context, fsId).writeText(imageRef)

    fun isCorrupt(context: Context, fsId: String): Boolean =
        corruptMarker(context, fsId).exists()

    fun markCorrupt(context: Context, fsId: String) {
        corruptMarker(context, fsId).createNewFile()
    }

    fun layersImageRef(context: Context, fsId: String): String? =
        File(layersDir(context, fsId), REF_FILE).takeIf { it.exists() }?.readText()?.trim()

    fun clearEntry(context: Context, fsId: String) {
        layersDir(context, fsId).deleteRecursively()
        File(qcow2Path(context, fsId)).delete()
        readyMarker(context, fsId).delete()
        corruptMarker(context, fsId).delete()
    }
}
