package nl.rass.player.player.subtitle.online

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import nl.rass.player.player.subtitle.SubtitleFormat

class ExtractedSubtitle(val fileName: String, val bytes: ByteArray) {
    val format: SubtitleFormat get() = SubtitleFormat.detect(url = fileName)
}

/** Safe in-memory unzip (R6.7): size/entry caps, no path traversal, only .srt/.vtt are considered. */
object SubtitleZip {
    const val MAX_UNCOMPRESSED_BYTES = 10L * 1024 * 1024
    const val MAX_ENTRIES = 50

    fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
        (b[2] == 3.toByte() || b[2] == 5.toByte())

    /**
     * If [data] is a ZIP, unpacks it and picks one subtitle (for series, preferring the file for
     * [season]/[episode]); otherwise returns [data] as is.
     */
    fun extract(data: ByteArray, season: Int? = null, episode: Int? = null, fallbackName: String = "subtitle.srt"): ExtractedSubtitle {
        if (!isZip(data)) return ExtractedSubtitle(fallbackName, data)

        val candidates = ArrayList<ExtractedSubtitle>()
        var total = 0L
        var entries = 0
        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw unsafe("آرشیو زیرنویس فایل‌های زیادی دارد.", OnlineSubtitleException.Kind.UNSAFE_ZIP)
                    val name = e.name
                    if (isTraversal(name)) throw unsafe("آرشیو زیرنویس ناامن است.", OnlineSubtitleException.Kind.UNSAFE_ZIP)
                    val wanted = !e.isDirectory && isSubtitleName(name)
                    val sink = if (wanted) ByteArrayOutputStream() else null
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNCOMPRESSED_BYTES) throw unsafe("آرشیو زیرنویس بیش از حد بزرگ است.", OnlineSubtitleException.Kind.TOO_LARGE)
                        sink?.write(buf, 0, n)
                    }
                    if (sink != null && sink.size() > 0) candidates += ExtractedSubtitle(name.substringAfterLast('/'), sink.toByteArray())
                }
            }
        } catch (e: ZipException) {
            throw OnlineSubtitleException(OnlineSubtitleException.Kind.BAD_RESPONSE, "آرشیو زیرنویس خراب است.", e)
        }
        return pick(candidates, season, episode)
            ?: throw OnlineSubtitleException(OnlineSubtitleException.Kind.NOT_FOUND, "فایل زیرنویس مناسبی در آرشیو پیدا نشد.")
    }

    internal fun pick(c: List<ExtractedSubtitle>, season: Int?, episode: Int?): ExtractedSubtitle? {
        if (c.isEmpty()) return null
        if (episode != null && c.size > 1) {
            val matching = c.filter { matchesEpisode(it.fileName, season, episode) }
            if (matching.isNotEmpty()) return best(matching)
            // A multi-episode pack without our episode: refuse rather than show the wrong episode.
            if (c.count { episodeOf(it.fileName) != null } > 1) return null
        }
        return best(c)
    }

    private fun best(c: List<ExtractedSubtitle>) =
        c.maxWithOrNull(compareBy<ExtractedSubtitle> { if (it.format == SubtitleFormat.SRT) 1 else 0 }.thenBy { it.bytes.size })

    private val SXXEYY = Regex("""(?i)(?<![a-z0-9])s(\d{1,3})[ ._-]?e(\d{1,4})""")
    private val NXM = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![a-z0-9])""")
    private val EP_ONLY = Regex("""(?i)(?<![a-z0-9])e(?:p(?:isode)?)?[ ._-]?(\d{1,4})(?![0-9])""")

    /** (season?, episode) found in a file name. */
    internal fun episodeOf(name: String): Pair<Int?, Int>? {
        SXXEYY.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        NXM.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        EP_ONLY.find(name)?.let { return null to it.groupValues[1].toInt() }
        return null
    }

    private fun matchesEpisode(name: String, season: Int?, episode: Int): Boolean {
        val (s, e) = episodeOf(name) ?: return false
        return e == episode && (s == null || season == null || s == season)
    }

    private fun isSubtitleName(name: String): Boolean {
        val base = name.substringAfterLast('/')
        if (name.startsWith("__MACOSX") || base.startsWith("._")) return false
        val l = base.lowercase()
        return l.endsWith(".srt") || l.endsWith(".vtt")
    }

    private fun isTraversal(name: String): Boolean {
        val n = name.replace('\\', '/')
        return n.startsWith("/") || n.split('/').any { it == ".." } || (n.length > 1 && n[1] == ':')
    }

    private fun unsafe(msg: String, kind: OnlineSubtitleException.Kind) = OnlineSubtitleException(kind, msg)
}
