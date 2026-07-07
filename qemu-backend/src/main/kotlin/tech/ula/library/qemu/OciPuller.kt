package tech.ula.library.qemu

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class OciPuller(private val context: Context, private val fsId: String) {

    data class ImageRef(val registry: String, val name: String, val tag: String)

    companion object {
        private const val TAG = "OciPuller"
        const val DOCKER_REGISTRY = "registry-1.docker.io"
        private const val DOCKER_AUTH_URL = "https://auth.docker.io/token"

        fun parseRef(raw: String): ImageRef {
            val s = raw.trim()
            val slashIdx = s.indexOf('/')
            val registry: String
            val nameTag: String

            if (slashIdx >= 0) {
                val first = s.substring(0, slashIdx)
                if (first.contains('.') || first.contains(':') || first == "localhost") {
                    registry = if (first == "docker.io") DOCKER_REGISTRY else first
                    nameTag  = s.substring(slashIdx + 1)
                } else {
                    registry = DOCKER_REGISTRY
                    nameTag  = s
                }
            } else {
                registry = DOCKER_REGISTRY
                nameTag  = "library/$s"
            }

            val colonIdx = nameTag.lastIndexOf(':')
            return if (colonIdx >= 0)
                ImageRef(registry, nameTag.substring(0, colonIdx), nameTag.substring(colonIdx + 1))
            else
                ImageRef(registry, nameTag, "latest")
        }
    }

    fun pull(imageRefStr: String, onProgress: (String) -> Unit = {}): Result<File> = runCatching {
        val ref = parseRef(imageRefStr)
        Log.i(TAG, "Pulling ${ref.registry}/${ref.name}:${ref.tag}")

        onProgress("Authenticating with ${ref.registry}…")
        val token = fetchToken(ref)

        onProgress("Fetching manifest…")
        val manifest = fetchManifest(ref, token)
        val layers   = manifest.getJSONArray("layers")

        val layersDir = OciManager.layersDir(context, fsId)
        layersDir.deleteRecursively()
        layersDir.mkdirs()

        val manifestLines = mutableListOf<String>()
        for (i in 0 until layers.length()) {
            val layer     = layers.getJSONObject(i)
            val digest    = layer.getString("digest")
            val sizeBytes = layer.optLong("size", -1)
            val fileName  = digest.replace(':', '_') + ".tar.gz"

            val n = i + 1
            val total = layers.length()
            var lastMsg = ""
            onProgress("Downloading layer $n/$total (0%)")

            val blobUrl = "https://${ref.registry}/v2/${ref.name}/blobs/$digest"
            val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
            httpStream(blobUrl, headers) { stream ->
                val tracked = ProgressCountingStream(stream, sizeBytes) { pct ->
                    val msg = "Downloading layer $n/$total ($pct%)"
                    if (msg != lastMsg) { lastMsg = msg; onProgress(msg) }
                }
                File(layersDir, fileName).outputStream().buffered(65536).use { tracked.copyTo(it) }
            }
            manifestLines += fileName
        }

        File(layersDir, "manifest.txt").writeText(manifestLines.joinToString("\n") + "\n")
        File(layersDir, ".oci_ref").writeText(imageRefStr)

        onProgress("Layers saved.")
        layersDir
    }

    private fun fetchToken(ref: ImageRef): String? {
        val authUrl = when (ref.registry) {
            DOCKER_REGISTRY -> "$DOCKER_AUTH_URL?service=registry.docker.io&scope=repository:${ref.name}:pull"
            else -> "https://${ref.registry}/token?service=${ref.registry}&scope=repository:${ref.name}:pull"
        }
        return try {
            JSONObject(httpGet(authUrl, emptyMap())).optString("token").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Token fetch failed (trying anonymous): ${e.message}")
            null
        }
    }

    private fun fetchManifest(ref: ImageRef, token: String?): JSONObject {
        val accept = listOf(
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.index.v1+json",
        ).joinToString(",")
        val headers = buildMap {
            put("Accept", accept)
            if (token != null) put("Authorization", "Bearer $token")
        }
        val url  = "https://${ref.registry}/v2/${ref.name}/manifests/${ref.tag}"
        val json = JSONObject(httpGet(url, headers))

        val mt = json.optString("mediaType", "")
        if (mt.contains("manifest.list") || mt.contains("image.index")) {
            val manifests = json.getJSONArray("manifests")
            for (arch in listOf("arm64", "amd64")) {
                for (i in 0 until manifests.length()) {
                    val m  = manifests.getJSONObject(i)
                    val pl = m.optJSONObject("platform") ?: continue
                    if (pl.optString("os") == "linux" && pl.optString("architecture") == arch) {
                        val digest = m.getString("digest")
                        val single = "https://${ref.registry}/v2/${ref.name}/manifests/$digest"
                        return JSONObject(httpGet(single, headers))
                    }
                }
            }
            throw IllegalStateException("No linux/arm64 or linux/amd64 manifest found in index")
        }
        return json
    }

    private fun httpGet(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 60_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode} from $url")
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    private fun httpStream(url: String, headers: Map<String, String>, block: (InputStream) -> Unit) {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout      = 30_000
        conn.readTimeout         = 300_000
        conn.instanceFollowRedirects = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connect()

        if (conn.responseCode in 301..308) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout    = 300_000
            conn.connect()
        }

        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode} from $url")
            conn.inputStream.use(block)
        } finally { conn.disconnect() }
    }

    // Reports read progress as a percentage of totalBytes (skips callbacks if totalBytes <= 0,
    // i.e. the manifest didn't declare a size for this layer).
    private class ProgressCountingStream(
        private val wrapped: InputStream,
        private val totalBytes: Long,
        private val onPercent: (Int) -> Unit
    ) : InputStream() {
        private var bytesRead = 0L
        private var lastPct = -1

        private fun track(n: Int) {
            if (n <= 0 || totalBytes <= 0) return
            bytesRead += n
            val pct = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (pct != lastPct) { lastPct = pct; onPercent(pct) }
        }

        override fun read(): Int {
            val b = wrapped.read()
            if (b >= 0) track(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = wrapped.read(b, off, len)
            track(n)
            return n
        }

        override fun close() = wrapped.close()
    }
}
