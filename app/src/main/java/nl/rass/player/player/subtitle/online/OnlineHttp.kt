package nl.rass.player.player.subtitle.online

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/** Cancellable call; the caller must close the response. */
internal suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(
                OnlineSubtitleException(
                    OnlineSubtitleException.Kind.NETWORK,
                    "اتصال به سرویس زیرنویس برقرار نشد.",
                    e,
                ),
            )
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/** Reads at most [max] bytes, or throws TOO_LARGE. */
internal fun ResponseBody.readCapped(max: Long): ByteArray {
    val len = contentLength()
    if (len > max) throw tooLarge()
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    byteStream().use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw tooLarge()
        }
    }
    return out.toByteArray()
}

private fun tooLarge() =
    OnlineSubtitleException(OnlineSubtitleException.Kind.TOO_LARGE, "فایل زیرنویس بیش از حد بزرگ است.")

internal const val MAX_DOWNLOAD_BYTES = 12L * 1024 * 1024
