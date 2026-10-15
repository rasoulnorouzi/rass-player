"""Generate synthetic test subtitles (our own text, no film dialogue) into assets/test-feeds/subs/.

Files (cue N sits at N*15 s, 40 cues, i.e. the first 10 minutes):
  bbb.fa.cp1256.srt    Persian, Windows-1256 bytes (no encoding hint in the feed -> tests auto-detect)
  bbb.en.vtt           English WebVTT
  bbb.en.srt           English SRT (in sync)
  bbb.en.late2s.srt    English SRT, every cue 2.0 s late (for testing the sync control)
  bbb.ru.srt           Russian (must be hidden by the app: only fa/en are shown)
  sintel.fa.srt        Persian, UTF-8 *with BOM*
  sintel.en.srt        English SRT
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

CUES = 40
STEP = 15.0   # seconds between cue starts
DUR = 3.5     # cue length

PERSIAN_DIGITS = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")


def fa_digits(s: str) -> str:
    return s.translate(PERSIAN_DIGITS)


def stamp_srt(t: float) -> str:
    ms = round(t * 1000)
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"


def stamp_vtt(t: float) -> str:
    return stamp_srt(t).replace(",", ".")


def mmss(t: float) -> str:
    return f"{int(t) // 60:02d}:{int(t) % 60:02d}"


def cues(kind: str):
    for n in range(1, CUES + 1):
        start = n * STEP - 10.0   # first cue at 5 s
        end = start + DUR
        at = mmss(start)
        if kind == "fa":
            text = f"زیرنویس آزمایشی شمارهٔ {fa_digits(str(n))} — {fa_digits(at)}"
            if n % 5 == 0:
                text += "\nخط دوم: test line داخل متن فارسی (bidi)"
        elif kind == "fa-ascii":  # for legacy code pages that have no Persian digits
            text = f"زیرنویس آزمایشی شماره {n} — {at}"  # no hamza mark: not in cp1256
            if n % 5 == 0:
                text += "\nخط دوم: test line داخل متن فارسی (bidi)"
        elif kind == "en":
            text = f"Test subtitle number {n} - {at}"
            if n % 5 == 0:
                text += "\nSecond line: a longer line to check wrapping and line spacing"
        elif kind == "ru":
            text = f"Тестовый субтитр номер {n} - {at}"
        else:
            raise ValueError(kind)
        yield n, start, end, text


def build_srt(kind: str, shift: float = 0.0) -> str:
    out = []
    for n, s, e, text in cues(kind):
        out.append(f"{n}\n{stamp_srt(s + shift)} --> {stamp_srt(e + shift)}\n{text}\n")
    return "\n".join(out)


def build_vtt(kind: str) -> str:
    out = ["WEBVTT\n"]
    for n, s, e, text in cues(kind):
        out.append(f"{n}\n{stamp_vtt(s)} --> {stamp_vtt(e)}\n{text}\n")
    return "\n".join(out)


def to_cp1256(text: str) -> bytes:
    # Legacy files use Arabic yeh/kaf (U+064A / U+0643); the app maps them back to Persian ی / ک.
    legacy = text.replace("ی", "ي").replace("ک", "ك")
    return legacy.encode("cp1256")


def main():
    d = common.SUBS_DIR
    d.mkdir(parents=True, exist_ok=True)
    files = {
        "bbb.fa.cp1256.srt": to_cp1256(build_srt("fa-ascii").replace("\n", "\r\n")),
        "bbb.en.vtt": build_vtt("en").encode("utf-8"),
        "bbb.en.srt": build_srt("en").encode("utf-8"),
        "bbb.en.late2s.srt": build_srt("en", shift=2.0).encode("utf-8"),
        "bbb.ru.srt": build_srt("ru").encode("utf-8"),
        "sintel.fa.srt": b"\xef\xbb\xbf" + build_srt("fa").encode("utf-8"),
        "sintel.en.srt": build_srt("en").encode("utf-8"),
    }
    for name, data in files.items():
        (d / name).write_bytes(data)
        common.log(f"wrote subs/{name} ({len(data)} bytes)")
    # Self-check: cp1256 file must not be valid UTF-8 (otherwise the auto-detect path is not exercised).
    try:
        files["bbb.fa.cp1256.srt"].decode("utf-8")
        raise SystemExit("cp1256 file unexpectedly valid UTF-8")
    except UnicodeDecodeError:
        pass


if __name__ == "__main__":
    main()
