package nl.rass.player.data.source

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** What the phone page collects. */
enum class LanMode { SOURCE, SUBSCRIPTION, TEXT }

/** Outcome of checking one request to the phone page (pure, see [LanEntryServer.evaluate]). */
sealed interface PhoneRequest {
    data object Forbidden : PhoneRequest
    data object ShowForm : PhoneRequest
    data class Invalid(val messageFa: String) : PhoneRequest
    data class Submit(val name: String, val value: String) : PhoneRequest
}

/**
 * R1.7: a tiny page on the LAN where the user types on the phone: a site/feed address ([LanMode.SOURCE]), an Xray
 * subscription link ([LanMode.SUBSCRIPTION]) or search text ([LanMode.TEXT]). The TV shows a QR code of the URL.
 * Random port, a one-time code in the URL, LAN only. Values are handed to [onSubmit] and never logged.
 * The code stops working after the first accepted submit or [stop].
 *
 * [onSubmit] gets (name, value) and returns null on success or a Persian error to show on the phone. It is
 * called on the server thread and may block.
 */
class LanEntryServer(
    private val mode: LanMode = LanMode.SOURCE,
    private val hostProvider: () -> String? = ::localIpv4,
    private val onSubmit: (name: String, value: String) -> String?,
) : NanoHTTPD(0) {

    @Volatile var code: String = newCode(); private set
    @Volatile private var used = false

    /** Starts listening and returns the URL for the QR code, or null when there is no LAN address. */
    fun startAndGetUrl(): String? {
        val host = hostProvider() ?: return null
        start(SOCKET_READ_TIMEOUT, false)
        return "http://$host:$listeningPort/?c=$code"
    }

    override fun serve(session: IHTTPSession): Response {
        val post = session.method == Method.POST
        if (post) runCatching { session.parseBody(HashMap()) }
        val t = texts()
        return when (val r = evaluate(mode, code, used, post, session.parameters)) {
            PhoneRequest.Forbidden -> html(Response.Status.FORBIDDEN, page("پیوند منقضی شده است", "این صفحه یک‌بار مصرف است. در تلویزیون دوباره QR بسازید.", form = false))
            PhoneRequest.ShowForm -> html(Response.Status.OK, page(t.title, t.prompt, form = true))
            is PhoneRequest.Invalid -> html(Response.Status.OK, page(t.title, r.messageFa, form = true))
            is PhoneRequest.Submit -> {
                val err = onSubmit(r.name, r.value)
                if (err == null) {
                    used = true
                    html(Response.Status.OK, page("انجام شد", t.done, form = false))
                } else html(Response.Status.OK, page("ناموفق", err, form = true))
            }
        }
    }

    private class Texts(val title: String, val prompt: String, val label: String, val done: String)

    private fun texts() = when (mode) {
        LanMode.SOURCE -> Texts("افزودن منبع به Rass_player", "نشانی سایت یا فید خود را وارد کنید.", "نشانی سایت یا فید", "منبع به تلویزیون اضافه شد. می‌توانید این صفحه را ببندید.")
        LanMode.SUBSCRIPTION -> Texts("افزودن اتصال خصوصی", "پیوند اشتراک (subscription) را جای‌گذاری کنید.", "پیوند اشتراک", "پیوند به تلویزیون اضافه شد. می‌توانید این صفحه را ببندید.")
        LanMode.TEXT -> Texts("تایپ با گوشی", "متن جستجو را بنویسید.", "متن جستجو", "متن به تلویزیون فرستاده شد. می‌توانید این صفحه را ببندید.")
    }

    private fun html(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", body).also {
            it.addHeader("Cache-Control", "no-store")
            it.addHeader("Referrer-Policy", "no-referrer")
        }

    private fun field(): String = when (mode) {
        LanMode.TEXT -> """<input name="value" dir="auto" autofocus>"""
        LanMode.SUBSCRIPTION -> """<input name="value" type="url" dir="ltr" placeholder="https://" autofocus>"""
        LanMode.SOURCE -> """<input name="value" type="url" dir="ltr" placeholder="https://mysite.nl" autofocus>"""
    }

    private fun page(title: String, message: String, form: Boolean): String = """
        <!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Rass_player</title>
        <style>body{font-family:sans-serif;background:#05070a;color:#fff;padding:24px;max-width:480px;margin:auto}
        input{width:100%;box-sizing:border-box;padding:14px;margin:8px 0 16px;font-size:18px;border-radius:8px;border:0}
        button{padding:14px 28px;font-size:18px;border:0;border-radius:24px;background:#dc2626;color:#fff}</style></head><body>
        <h2>${esc(title)}</h2><p>${esc(message)}</p>
        ${if (form) """<form method="post" action="/?c=${esc(code)}">
        <label>${esc(texts().label)}</label>${field()}
        ${if (mode != LanMode.TEXT) """<label>نام (اختیاری)</label><input name="name">""" else ""}
        <button type="submit">ارسال</button></form>""" else ""}
        </body></html>
    """.trimIndent()

    companion object {
        const val MAX_TEXT = 200
        private const val MAX_NAME = 80
        private val whitespace = Regex("\\s+")
        private val rnd = SecureRandom()
        fun newCode(): String = buildString { repeat(10) { append("abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)]) } }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Checks one request. [params] = query + form fields; the value may arrive as `value` or legacy `address`. */
        fun evaluate(mode: LanMode, expectedCode: String, used: Boolean, post: Boolean, params: Map<String, List<String>>): PhoneRequest {
            if (used || params["c"]?.firstOrNull() != expectedCode) return PhoneRequest.Forbidden
            if (!post) return PhoneRequest.ShowForm
            val raw = (params["value"] ?: params["address"])?.firstOrNull().orEmpty()
            val value = if (mode == LanMode.TEXT) raw.trim() else raw.trim().replace(whitespace, "")
            val name = params["name"]?.firstOrNull()?.trim().orEmpty()
            if (value.isEmpty()) return PhoneRequest.Invalid("مقدار خالی است.")
            if (name.length > MAX_NAME) return PhoneRequest.Invalid("نام خیلی بلند است.")
            return when (mode) {
                LanMode.TEXT ->
                    if (value.length > MAX_TEXT) PhoneRequest.Invalid("متن خیلی بلند است.") else PhoneRequest.Submit("", value)
                LanMode.SUBSCRIPTION ->
                    if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
                        PhoneRequest.Invalid("پیوند باید با http:// یا https:// شروع شود.")
                    } else PhoneRequest.Submit(name, value)
                LanMode.SOURCE -> PhoneRequest.Submit(name, value)
            }
        }

        /** First private-range IPv4 address of an up, non-loopback interface. */
        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
