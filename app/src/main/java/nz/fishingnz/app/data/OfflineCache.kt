package nz.fishingnz.app.data

import android.content.Context
import nz.fishingnz.app.model.GeoPoint
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Last good provider responses kept in private app storage, so tides, forecasts and rules still show without a signal.
 * Keys are file names; the file's modified time is when the response was saved. The iPhone app has `OfflineCache.swift`.
 */
object OfflineCache {
    @Volatile private var folder: File? = null

    fun initialize(context: Context) { folder = context.filesDir.resolve("offline").apply { mkdirs() } }

    /** Overrides the folder in unit tests. */
    internal fun useFolder(dir: File?) { folder = dir?.apply { mkdirs() } }

    fun write(key: String, text: String) = writeBytes(key, text.toByteArray())

    /** Saves [bytes] under [key], which may name a subfolder such as "species/snapper.webp". */
    fun writeBytes(key: String, bytes: ByteArray) {
        val dir = folder ?: return
        runCatching {
            val target = dir.resolve(key)
            target.parentFile?.mkdirs()
            val temporary = dir.resolve("$key.tmp")
            temporary.writeBytes(bytes)
            temporary.renameTo(target)
        }
    }

    /** The saved file for [key], or null. */
    fun file(key: String): File? = folder?.resolve(key)?.takeIf(File::isFile)

    fun delete(key: String) { folder?.resolve(key)?.delete() }

    /** The saved text and when it was saved, or null. */
    fun read(key: String): Pair<String, Instant>? {
        val file = folder?.resolve(key)?.takeIf(File::isFile) ?: return null
        return runCatching { file.readText() to Instant.ofEpochMilli(file.lastModified()) }.getOrNull()
    }

    /** A place's key: coordinates to about 100 m, so nearby requests share one saved forecast. */
    fun placeKey(kind: String, point: GeoPoint): String =
        String.format(Locale.US, "%s_%.3f_%.3f.json", kind, point.latitude, point.longitude)

    /** "Sat 18 Oct, 3:10 PM", in New Zealand time. */
    fun savedLabel(at: Instant): String =
        at.atZone(ZoneId.of("Pacific/Auckland")).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.US))
}
