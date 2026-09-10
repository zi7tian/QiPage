#!/usr/bin/env python3
"""Generate the self-made TXT / EPUB test fixtures used by the test suites.

Every book produced here is synthetic. No user-supplied or copyrighted text is
ever written, so the generated corpus is safe to publish.

The fixtures deliberately include hostile inputs (path traversal, in-book
script, remote resources, fixed layout) so the sanitizer and the ZIP guards can
be tested against them.

Usage:
    python3 scripts/gen_fixtures.py [--output-dir DIR] [--include-large-txt]

"""
from __future__ import annotations

import argparse
import struct
import sys
import zipfile
import zlib
from pathlib import Path

# Fixture entries carry a fixed timestamp so regenerating them is byte-stable
# and does not create spurious diffs.
FIXED_DATE = (2026, 9, 6, 0, 0, 0)

CONTAINER_XML = (
    '<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:'
    'xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/book.opf" '
    'media-type="application/oebps-package+xml"/></rootfiles></container>'
)

STYLE_CSS = (
    "body { background: #f7f3ea; color: #292d28; font-size: 20px; line-height: 1.8; "
    "margin: 24px; } h1 {font-size: 26px;} img {width: 32px; height: 32px;}"
)

NAV_XHTML = (
    '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
    "<head><title>目录</title></head><body><nav epub:type=\"toc\"><ol>"
    '<li><a href="ch1.xhtml">第一章</a><ol>'
    '<li><a href="ch2.xhtml#end">第二章</a></li></ol></li></ol></nav></body></html>'
)

TOC_NCX = (
    '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/>'
    "<docTitle><text>山间书页</text></docTitle><navMap>"
    '<navPoint id="c1" playOrder="1"><navLabel><text>第一章</text></navLabel>'
    '<content src="ch1.xhtml"/></navPoint>'
    '<navPoint id="c2" playOrder="2"><navLabel><text>第二章</text></navLabel>'
    '<content src="ch2.xhtml"/></navPoint></navMap></ncx>'
)

CH1_XHTML = (
    '<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml">'
    "<head><title>第一章</title><link rel=\"stylesheet\" href=\"style.css\"/></head>"
    '<body><h1>第一章 山间书页</h1><p id="marker">SCRIPT_NOT_EXECUTED</p>'
    "<p>风从窗外吹进来，书页轻轻翻动。这是完全本地的测试正文。</p>"
    '<img src="dot.png" alt="本地图片"/><p><a href="ch2.xhtml#end">前往第二章</a></p>'
    "{extra_html}</body></html>"
)

CH2_XHTML = (
    '<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">'
    "<head><title>第二章</title><link rel=\"stylesheet\" href=\"style.css\"/></head>"
    '<body><h1 id="end">第二章 雨停之后</h1><p>EPUB_CHAPTER_TWO_OK</p>'
    "<p>雨停之后，他把书放回原处。</p><a href=\"ch1.xhtml\">返回第一章</a></body></html>"
)

SCRIPT_REMOTE_EXTRA = (
    '<script>document.getElementById("marker").textContent="SCRIPT_EXECUTED";</script>'
    '<img src="https://example.invalid/pixel.png"/>'
    '<iframe src="https://example.invalid/frame"/>'
    '<a href="https://example.invalid/">外部链接</a>'
)


def solid_png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """Encode a solid-colour 32-bit RGBA PNG with no imaging dependency.

    RGBA rather than RGB, because that is what the original GDI+ based fixture
    generator emitted. The byte stream differs but the decoded pixels are
    identical, which is what the screenshot assertions actually compare.
    """
    r, g, b = rgb
    raw = b"".join(b"\x00" + bytes((r, g, b, 255)) * width for _ in range(height))

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        # sRGB / gAMA / pHYs mirror the ancillary chunks the original GDI+
        # encoder emitted, so the fixture stays structurally comparable.
        + chunk(b"sRGB", b"\x00")
        + chunk(b"gAMA", struct.pack(">I", 45455))
        + chunk(b"pHYs", struct.pack(">IIB", 3779, 3779, 1))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def book_opf(name: str, version: str, extra_meta: str) -> str:
    opf = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<package xmlns="http://www.idpf.org/2007/opf" version="{v}" unique-identifier="id">\n'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        '<dc:identifier id="id">urn:uuid:readapp-p0-{name}</dc:identifier>'
        "<dc:title>山间书页 · P0</dc:title><dc:language>zh</dc:language>"
        "<dc:creator>ReadApp 自制测试样本</dc:creator>"
        '<meta property="dcterms:modified">2026-09-06T00:00:00Z</meta>{meta}</metadata>\n'
        "<manifest>"
        '<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>'
        '<item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>'
        '<item id="css" href="style.css" media-type="text/css"/>'
        '<item id="image" href="dot.png" media-type="image/png"/>'
        '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
        "</manifest>\n"
        '<spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine></package>'
    ).format(v=version, name=name, meta=extra_meta)

    if version == "2.0":
        opf = opf.replace(' properties="nav"', "").replace(
            '<meta property="dcterms:modified">2026-09-06T00:00:00Z</meta>', ""
        )
    return opf


def write_zip(path: Path, entries: list[tuple[str, bytes]], *, store_mimetype: bool) -> None:
    """Write an EPUB.

    `mimetype` must be the first entry and stored uncompressed, otherwise the
    file is not a conforming EPUB and strict readers reject it.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.unlink()

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, payload in entries:
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            if name == "mimetype" and store_mimetype:
                info.compress_type = zipfile.ZIP_STORED
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, payload)


def write_epub(
    out: Path,
    name: str,
    *,
    version: str = "3.0",
    extra_html: str = "",
    extra_meta: str = "",
    bad_entry: str = "",
) -> None:
    entries: list[tuple[str, bytes]] = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER_XML.encode("utf-8")),
        ("OEBPS/book.opf", book_opf(name, version, extra_meta).encode("utf-8")),
        ("OEBPS/ch1.xhtml", CH1_XHTML.format(extra_html=extra_html).encode("utf-8")),
        ("OEBPS/ch2.xhtml", CH2_XHTML.encode("utf-8")),
        ("OEBPS/style.css", STYLE_CSS.encode("utf-8")),
        ("OEBPS/nav.xhtml", NAV_XHTML.encode("utf-8")),
        ("OEBPS/toc.ncx", TOC_NCX.encode("utf-8")),
        ("OEBPS/dot.png", solid_png(32, 32, (34, 139, 34))),
    ]
    if bad_entry:
        entries.append((bad_entry, b"must be rejected"))
    write_zip(out / name, entries, store_mimetype=True)


# ---------------------------------------------------------------------------
# P0: engine-selection corpus
# ---------------------------------------------------------------------------
def gen_p0(out: Path, include_large_txt: bool) -> None:
    write_epub(out, "epub3-basic.epub")
    write_epub(out, "epub2-basic.epub", version="2.0")
    write_epub(out, "script-remote.epub", extra_html=SCRIPT_REMOTE_EXTRA)
    write_epub(
        out,
        "fixed-layout.epub",
        extra_meta='<meta property="rendition:layout">pre-paginated</meta>',
    )
    write_epub(out, "zip-traversal.epub", bad_entry="../escape.txt")

    (out / "utf8-small.txt").write_bytes(
        "第一章 山间书页\r\n本地阅读测试。😀\n第二章 雨停之后\nEND_OF_BOOK".encode("utf-8")
    )
    (out / "gb18030-small.txt").write_bytes(
        "第一章 中文编码\r\n山风吹过书页。😀\nGB18030_END".encode("gb18030")
    )

    if include_large_txt:
        block = ("第一章 本地阅读\r\n" + ("山风吹过书页。😀" * 1000) + "\n").encode("utf-8")
        for size_mib in (5, 100):
            target = size_mib * 1024 * 1024
            with (out / f"utf8-{size_mib}-MiB.txt").open("wb") as fh:
                written = 0
                while written < target:
                    fh.write(block)
                    written += len(block)


def gen_saf(out: Path) -> None:
    # No trailing newline, LF endings: the SAF test asserts on the final marker.
    (out / "saf-local.txt").write_bytes(
        "系统本机存储导入验证。\n长期授权与离线阅读。\nSAF_LOCAL_OK".encode("utf-8")
    )


# ---------------------------------------------------------------------------
# P2: long book with anchors and a cover-image book
# ---------------------------------------------------------------------------
def gen_p2(out: Path) -> None:
    one = ['<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>EPUB_START</h1>']
    two = ['<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第二章</h1>']
    for i in range(180):
        one.append(
            f'<p id="a{i}">第 {i} 段。山间的风吹过书页，阅读留在本机。中文与表情😀。这是可重排正文。</p>'
        )
        if i == 90:
            two.append('<h2 id="middle">P2_ANCHOR_TARGET</h2><img src="dot.png" alt="本地图像"/>')
        two.append(
            f'<p id="b{i}">第二节第 {i} 段。雨停之后，他继续阅读。改变字号仍应回到相同文字附近。</p>'
        )
    one.append('<a href="ch2.xhtml#middle">跳到中段</a></body></html>')
    two.append("</body></html>")
    ch1_html, ch2_html = "".join(one), "".join(two)

    # Derived from the epub3-basic OPF, so the identifier keeps that book's
    # name. P2 fixtures are edited copies of the P0 book, not new books.
    base_opf = book_opf("epub3-basic.epub", "3.0", "").replace("山间书页 · P0", "P2本地样书")
    nav = (
        '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
        '<body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">第一章</a><ol>'
        '<li><a href="ch2.xhtml#middle">第二章 · 中段</a></li>'
        "</ol></li></ol></nav></body></html>"
    )

    common = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER_XML.encode("utf-8")),
        ("OEBPS/style.css", STYLE_CSS.encode("utf-8")),
        ("OEBPS/nav.xhtml", nav.encode("utf-8")),
        ("OEBPS/toc.ncx", TOC_NCX.encode("utf-8")),
        ("OEBPS/dot.png", solid_png(32, 32, (34, 139, 34))),
    ]

    write_zip(
        out / "p2-long.epub",
        common
        + [
            ("OEBPS/book.opf", base_opf.encode("utf-8")),
            ("OEBPS/ch1.xhtml", ch1_html.encode("utf-8")),
            ("OEBPS/ch2.xhtml", ch2_html.encode("utf-8")),
        ],
        store_mimetype=True,
    )

    cover_opf = (
        base_opf.replace("P2本地样书", "P2封面样书")
        .replace('id="image"', 'id="image" properties="cover-image"')
    )
    cover_ch1 = (
        '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>P2_COVER_OK</h1>'
        "<p>书架封面使用本机缩略图。</p>"
        '<img src="dot.png" alt="绿色本地图片"/></body></html>'
    )
    write_zip(
        out / "p2-cover.epub",
        common
        + [
            ("OEBPS/book.opf", cover_opf.encode("utf-8")),
            ("OEBPS/ch1.xhtml", cover_ch1.encode("utf-8")),
            ("OEBPS/ch2.xhtml", ch2_html.encode("utf-8")),
        ],
        store_mimetype=True,
    )


# ---------------------------------------------------------------------------
# P4: layout / pagination corpus
# ---------------------------------------------------------------------------
def gen_p4(out: Path) -> None:
    lines = ["山川河流与晨光映入书页。"] * 160
    body = "\n".join(lines)

    txt = (
        "第一章 山间清晨\n"
        + body
        + "\n第二章 短章\n此章到此结束。\n第三章 再出发\n"
        + body
    )
    (out / "p4-layout.txt").write_bytes(txt.encode("utf-8"))

    opf = (
        '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        '<dc:identifier id="id">p4-layout</dc:identifier>'
        "<dc:title>P4排版样书</dc:title><dc:language>zh</dc:language></metadata>"
        '<manifest><item id="body" href="body.xhtml" media-type="application/xhtml+xml"/>'
        '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        '</manifest><spine><itemref idref="body"/></spine></package>'
    )
    nav = (
        '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
        '<body><nav epub:type="toc"><ol>'
        '<li><a href="body.xhtml#one">第一章 山间清晨</a></li>'
        '<li><a href="body.xhtml#two">第二章 短章</a></li>'
        '<li><a href="body.xhtml#three">第三章 再出发</a></li>'
        "</ol></nav></body></html>"
    )
    body_xhtml = (
        '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
        '<h1 id="one">第一章 山间清晨</h1>'
        + " ".join(f"<p>{line}</p>" for line in lines)
        + '<h1 id="two">第二章 短章</h1><p>此章到此结束。</p>'
        '<h1 id="three">第三章 再出发</h1>'
        + " ".join(f"<p>{line}</p>" for line in lines)
        + "</body></html>"
    )

    write_zip(
        out / "p4-layout.epub",
        [
            ("mimetype", b"application/epub+zip"),
            (
                "META-INF/container.xml",
                b'<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                b'<rootfiles><rootfile full-path="book.opf" '
                b'media-type="application/oebps-package+xml"/></rootfiles></container>',
            ),
            ("book.opf", opf.encode("utf-8")),
            ("nav.xhtml", nav.encode("utf-8")),
            ("body.xhtml", body_xhtml.encode("utf-8")),
        ],
        store_mimetype=True,
    )


# ---------------------------------------------------------------------------
# 0.3.1: manual chapter-rule corpus
# ---------------------------------------------------------------------------
def gen_031(out: Path) -> None:
    # Reproduce the original mixed line endings exactly: the first two lines end
    # with a bare LF, every line from 第二章 onwards ends with CRLF. This mixed
    # input is the point of the fixture - the TXT reader must normalise both.
    parts = [
        "第一章 暑假来了\n",
        "FIRST_END 这一章很短，下面应保持空白。\n",
        "第二章 新的一天\r\n",
    ]
    parts += [
        f"SECOND_BODY 第 {i} 段，测试连续翻页和章节之间的留白。"
        "我们在书中旅行，也在每一页保存自己的位置。\r\n"
        for i in range(1, 101)
    ]
    parts += ["第三章 归来\r\n", "THIRD_END 回到这里。\r\n"]
    (out / "patch-chapters.txt").write_bytes("".join(parts).encode("utf-8"))


def gen_many_chapters(out: Path) -> None:
    """A long table of contents.

    The reader only shows its fast-scroll bar when the list is actually
    scrollable, so exercising that needs a book with far more chapters than fit
    on one screen.
    """
    parts = []
    for i in range(1, 301):
        parts.append(f"第{i}章 测试章节{i}")
        parts.append(f"MANY_CHAPTER_{i} 这是第 {i} 章的正文，用于验证长目录的快速滚动。")
        parts.append("山川河流与晨光映入书页，阅读留在本机。")
    (out / "many-chapters.txt").write_bytes(("\n".join(parts) + "\n").encode("utf-8"))


def gen_p3(out: Path) -> None:
    # Single CRLF-terminated line, because this fixture is produced by writing a
    # directory listing entry on Windows and the P3 test matches it verbatim.
    (out / "p3-directory.txt").write_bytes(
        "P3_DIRECTORY_LOCAL 本机目录导入验证。\r\n".encode("utf-8")
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    default_out = Path(__file__).resolve().parent.parent / "fixtures" / "generated"
    parser.add_argument("--output-dir", default=str(default_out))
    parser.add_argument(
        "--include-large-txt",
        action="store_true",
        help="also generate the ~5 MiB and ~100 MiB UTF-8 stress samples",
    )
    args = parser.parse_args()

    out = Path(args.output_dir).resolve()
    out.mkdir(parents=True, exist_ok=True)

    gen_p0(out, args.include_large_txt)
    gen_saf(out)
    gen_p2(out)
    gen_many_chapters(out)
    gen_p3(out)
    gen_p4(out)
    gen_031(out)

    for path in sorted(out.iterdir()):
        if path.is_file():
            print(f"{path.name}\t{path.stat().st_size}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
