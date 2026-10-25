package app.falak.player.data

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException

/** How far a playlist download has got (the loading screen shows it). */
data class LoadProgress(
    /** Channels, films and series read so far. */
    val count: Int = 0,
    /** The provider's server failed part-way and the list is being read again: try 2 or 3 (0: the first try). */
    val retry: Int = 0,
)

/** The server answered with an error status (told apart from a connection that dropped). */
class HttpStatusException(val code: Int) : IOException("Server returned HTTP $code")

/**
 * Big playlists on busy providers: a list that fails part-way (a timeout, a dropped connection, a
 * server error) is read again from the start, up to [TRIES] tries with a short pause between them.
 * A refusal (HTTP 4xx) or an address that doesn't exist isn't tried again.
 */
object Downloads {
    const val TRIES = 3

    /** Pause before the second try; the third waits twice as long. */
    @Volatile var pauseMs = 3_000L

    fun worthRetrying(e: IOException): Boolean = when (e) {
        is HttpStatusException -> e.code in 500..599 || e.code == 429
        is UnknownHostException -> false
        else -> true
    }

    /**
     * [block], tried again when it fails with a passing problem. [onRetry] gets the try about to
     * start (2, 3) and undoes what the failed try left behind.
     */
    fun <T> withRetries(onRetry: (attempt: Int) -> Unit = {}, block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (attempt >= TRIES || !worthRetrying(e)) throw e
                attempt++
                onRetry(attempt)
                Thread.sleep(pauseMs * (attempt - 1))
            }
        }
    }

    /** Plain words for a network failure ("timeout", "unexpected end of stream"); other messages stay. */
    fun explain(e: IOException): IOException = when (e) {
        is HttpStatusException -> e
        is InterruptedIOException -> IOException("Your provider's server took too long to answer. Try again in a few minutes.", e)
        is UnknownHostException -> IOException("Couldn't reach the server. Check the internet connection and the address.", e)
        is ConnectException -> IOException("The server didn't answer. Check the address, or try again in a few minutes.", e)
        is EOFException, is ProtocolException, is SocketException ->
            IOException("The connection to your provider dropped part-way through the playlist. Try again.", e)
        else -> e
    }
}
