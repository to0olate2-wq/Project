package app.falak.player.player

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Auto captions' speech model (English, with capitals and punctuation): the Kroko-ASR streaming
 * model by Banafo (CC BY-SA 4.0) for sherpa-onnx. Not part of the app: about 71 MB, downloaded
 * once when auto captions are first turned on, into the app's own storage (never backed up).
 */
object SpeechModel {
    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/"

    /** The model's files, small ones first, with their approximate sizes (for the progress bar). */
    private val FILES = listOf(
        "tokens.txt" to 6_400L,
        "joiner.onnx" to 337_000L,
        "decoder.onnx" to 617_000L,
        "encoder.onnx" to 70_100_000L,
    )
    const val SIZE_LABEL = "71 MB"
    const val CREDIT = "Kroko-ASR speech model by Banafo (CC BY-SA 4.0)"

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun dir(context: Context): File = File(context.noBackupFilesDir, "speech/en-kroko")

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** Every file is in place (written last, after all of them arrived whole). */
    fun isReady(context: Context): Boolean = File(dir(context), "complete").exists()

    /** Brings [state] in line with what's on the device (not while a download runs). */
    fun refresh(context: Context) {
        if (job?.isActive == true) return
        val now = _state.value
        _state.value = when {
            isReady(context) -> State.Ready
            now is State.Failed -> now
            else -> State.Missing
        }
    }

    /** Downloads the model in the background (carries on if the screen that asked closes). */
    fun download(context: Context, http: OkHttpClient) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        if (isReady(app)) {
            _state.value = State.Ready
            return
        }
        _state.value = State.Downloading(0f)
        job = scope.launch {
            try {
                val d = dir(app).apply { mkdirs() }
                if (d.usableSpace < 200L * 1024 * 1024) throw IOException("Not enough free space: it needs about 200 MB")
                val total = FILES.sumOf { it.second }.toFloat()
                var done = 0L
                for ((name, approx) in FILES) {
                    val part = File(d, "$name.part")
                    fetch(http, BASE + name, part) { got ->
                        _state.value = State.Downloading(((done + minOf(got, approx)) / total).coerceIn(0f, 0.99f))
                    }
                    val target = File(d, name)
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("Couldn't save the speech model")
                    done += approx
                }
                File(d, "complete").writeText(BASE)
                _state.value = State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dir(app).listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                _state.value = State.Failed(e.message ?: "The download failed")
            }
        }
    }

    /** Frees the space; auto captions need the download again afterwards. */
    fun remove(context: Context) {
        job?.cancel()
        dir(context).deleteRecursively()
        _state.value = State.Missing
    }

    private fun fetch(http: OkHttpClient, url: String, out: File, progress: (Long) -> Unit) {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("The download server answered ${res.code}")
            val body = res.body ?: throw IOException("Empty download")
            val expected = body.contentLength()
            // Hugging Face gives a large file's SHA-256 on its first answer (the redirect to its store).
            val sha = (res.priorResponse?.header("X-Linked-Etag") ?: res.header("X-Linked-Etag"))
                ?.trim()?.removePrefix("W/")?.trim('"')?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            val digest = MessageDigest.getInstance("SHA-256")
            var got = 0L
            var shown = 0L
            out.outputStream().use { o ->
                body.byteStream().use { i ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        got += n
                        if (got - shown > 512 * 1024) {
                            shown = got
                            progress(got)
                        }
                    }
                }
            }
            if (expected > 0 && got != expected) throw IOException("The download was cut short: please try again")
            if (got == 0L) throw IOException("The download was empty")
            if (sha != null) {
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != sha) throw IOException("The download was damaged: please try again")
            }
        }
    }
}
