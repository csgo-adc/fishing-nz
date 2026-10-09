package nz.fishingnz.app.data

import kotlinx.coroutines.CancellationException
import nz.fishingnz.app.model.Recommendation

/** The two calls the service offers for short links. A fake stands in for it in tests. */
interface ShareBackend {
    /** The short id the service made for [token], or null when it could not. */
    suspend fun create(token: String): String?

    /** The token a short id stands for, or null when it is unknown, expired or the service could not be reached. */
    suspend fun read(id: String): String?
}

/** Turns a window into a link people can share, and a link the app was opened with back into a window. */
class ShareService(private val backend: ShareBackend) {
    /**
     * The link to share for [window]: a short link when the service answers, otherwise the long link that holds the whole
     * window, so sharing works offline too. Null when the window has no start time.
     */
    suspend fun linkFor(window: Recommendation): String? {
        val token = SharedWindowLink.encode(window) ?: return null
        val id = backend.create(token)
        return if (id != null && SharedWindowLink.isShortId(id)) SharedWindowLink.shortUrl(id) else "${SharedWindowLink.ORIGIN}/w/$token"
    }

    /** The window behind a token from a link: read from the address, or fetched when it is a short id. */
    suspend fun resolve(token: String): Recommendation? {
        val full = if (SharedWindowLink.isShortId(token)) backend.read(token) ?: return null else token
        return SharedWindowLink.decode(full)
    }

    companion object {
        val live by lazy { ShareService(RepositoryShareBackend(FishingRepository())) }
    }
}

private class RepositoryShareBackend(private val repository: FishingRepository) : ShareBackend {
    override suspend fun create(token: String): String? = attempt { repository.createShare(token).optString("id").ifBlank { null } }
    override suspend fun read(id: String): String? = attempt { repository.readShare(id).optString("token").ifBlank { null } }

    private suspend fun <T> attempt(call: suspend () -> T?): T? = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
