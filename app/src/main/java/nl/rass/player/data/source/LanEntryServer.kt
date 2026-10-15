package nl.rass.player.data.source

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * R1.7: a tiny page on the LAN where the user types a site/feed address on the phone. The TV shows a QR code of
 * [pageUrl]. Random port, a one-time code in the URL, only the address and an optional name travel over the
 * LAN (never tokens). The code stops working after the first accepted submit or [stop].
 *
 * [onSubmit] gets (name, address) and returns null on success or a Persian error to show on the phone. It is
 * called on the server thread and may block.
 */
class LanEntryServer(
    private val hostProvider: () -> String? = ::localIpv4,
    private val onSubmit: (name: String, address: String) -> String?,
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
        val params = session.parameters
        if (session.method == Method.POST) {
            runCatching { session.parseBody(HashMap()) }
        }
        val c = (session.parameters["c"] ?: params["c"])?.firstOrNull()
        if (used || c != code) return html(Response.Status.FORBIDDEN, page("پیوند منقضی شده است", "این صفحه یک‌بار مصرف است. در تلویزیون دوباره QR بسازید.", form = false))
        return if (session.method == Method.POST) {
            val address = session.parameters["address"]?.firstOrNull()?.trim().orEmpty()
            val name = session.parameters["name"]?.firstOrNull()?.trim().orEmpty()
            if (address.isEmpty()) return html(Response.Status.OK, page("نشانی خالی است", "نشانی سایت را وارد کنید.", form = true))
            val err = onSubmit(name, address)
            if (err == null) {
                used = true
                html(Response.Status.OK, page("اضافه شد", "منبع به تلویزیون اضافه شد. می‌توانید این صفحه را ببندید.", form = false))
            } else html(Response.Status.OK, page("ناموفق", err, form = true))
        } else {
            html(Response.Status.OK, page("افزودن منبع به Rass_player", "نشانی سایت یا فید خود را وارد کنید.", form = true))
        }
    }

    private fun html(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", body).also {
            it.addHeader("Cache-Control", "no-store")
            it.addHeader("Referrer-Policy", "no-referrer")
        }

    private fun page(title: String, message: String, form: Boolean): String = """
        <!doctype html><html lang="fa" dir="rtl"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Rass_player</title>
        <style>body{font-family:sans-serif;background:#05070a;color:#fff;padding:24px;max-width:480px;margin:auto}
        input{width:100%;box-sizing:border-box;padding:14px;margin:8px 0 16px;font-size:18px;border-radius:8px;border:0}
        button{padding:14px 28px;font-size:18px;border:0;border-radius:24px;background:#dc2626;color:#fff}</style></head><body>
        <h2>${esc(title)}</h2><p>${esc(message)}</p>
        ${if (form) """<form method="post" action="/?c=${esc(code)}">
        <label>نشانی سایت یا فید</label><input name="address" type="url" dir="ltr" placeholder="https://mysite.nl" autofocus>
        <label>نام (اختیاری)</label><input name="name">
        <button type="submit">افزودن</button></form>""" else ""}
        </body></html>
    """.trimIndent()

    companion object {
        private val rnd = SecureRandom()
        fun newCode(): String = buildString { repeat(10) { append("abcdefghijkmnpqrstuvwxyz23456789"[rnd.nextInt(32)]) } }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
