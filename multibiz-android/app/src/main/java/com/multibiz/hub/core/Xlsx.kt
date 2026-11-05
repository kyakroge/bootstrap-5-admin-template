package com.multibiz.hub.core

import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free .xlsx writer (one sheet, bold frozen header, filters).
 * All text is written as inline strings, so phone numbers like +919876543210 stay
 * exactly as text (no scientific notation, never interpreted as formulas).
 */
object Xlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val MAX_CELL = 32_000

    class Column(val title: String, val width: Double, val wrap: Boolean = false)

    fun build(sheetName: String, columns: List<Column>, rows: List<List<Any?>>): ByteArray {
        val name = sheetName.replace(Regex("[\\[\\]:*?/\\\\']"), " ").trim().take(31).ifEmpty { "Sheet1" }
        val lastCol = col(columns.size - 1)
        val lastRow = rows.size + 1
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(path: String, content: String) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${esc(name)}" sheetId="1" r:id="rId1"/></sheets><definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'${esc(name)}'!${'$'}A${'$'}1:${'$'}$lastCol${'$'}$lastRow</definedName></definedNames></workbook>"""
            )
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)

            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val w = OutputStreamWriter(z, Charsets.UTF_8)
            w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            w.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            w.write("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            w.write("""<sheetFormatPr defaultRowHeight="15"/><cols>""")
            columns.forEachIndexed { i, c ->
                w.write("""<col min="${i + 1}" max="${i + 1}" width="${c.width}" customWidth="1"/>""")
            }
            w.write("</cols><sheetData>")
            w.write("""<row r="1">""")
            columns.forEachIndexed { i, c -> w.write(cell(i, 1, c.title, 1)) }
            w.write("</row>")
            rows.forEachIndexed { ri, row ->
                val r = ri + 2
                w.write("""<row r="$r">""")
                row.forEachIndexed { ci, v ->
                    if (ci < columns.size && v != null) w.write(cell(ci, r, v, if (columns[ci].wrap) 2 else 0))
                }
                w.write("</row>")
            }
            w.write("</sheetData>")
            w.write("""<autoFilter ref="A1:$lastCol$lastRow"/>""")
            w.write("</worksheet>")
            w.flush()
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun cell(c: Int, r: Int, v: Any, style: Int): String {
        val ref = "${col(c)}$r"
        val s = if (style > 0) """ s="$style"""" else ""
        return if (v is Number) {
            """<c r="$ref"$s><v>$v</v></c>"""
        } else {
            val text = v.toString().let { if (it.length > MAX_CELL) it.take(MAX_CELL) + "…" else it }
            """<c r="$ref" t="inlineStr"$s><is><t xml:space="preserve">${esc(text)}</t></is></c>"""
        }
    }

    /** XML-escape and drop characters that are illegal in XML 1.0 (keeps emoji). */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> {}
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { sb.append(ch).append(s[i + 1]); i++ }
                }
                Character.isLowSurrogate(ch) -> {}
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF00695C"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
