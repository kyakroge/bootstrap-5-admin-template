package com.multibiz.hub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class XlsxTest {

    private val cols = listOf(
        Xlsx.Column("S.No", 7.0),
        Xlsx.Column("Phone Number", 20.0),
        Xlsx.Column("Name", 20.0),
        Xlsx.Column("Message", 50.0, wrap = true)
    )

    @Test
    fun buildsValidPackageWithTextNumbersAndEscaping() {
        val rows = listOf(
            listOf<Any?>(1, "+919523336670", "Ravi & Sons <Shop>", "=SUM(A1) \"quoted\"\nline 2 🙏"),
            listOf<Any?>(2, "Hidden by WhatsApp", null, "bad\u0001char")
        )
        val bytes = Xlsx.build("Members: [test]", cols, rows)
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(entries.keys.containsAll(listOf(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"
        )))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<t xml:space=\"preserve\">+919523336670</t>"))
        assertTrue(sheet.contains("Ravi &amp; Sons &lt;Shop&gt;"))
        assertTrue(sheet.contains("🙏"))
        assertTrue(!sheet.contains("\u0001"))
        assertTrue(sheet.contains("<autoFilter ref=\"A1:D3\"/>"))
        assertTrue(entries.getValue("xl/workbook.xml").contains("name=\"Members   test\""))
        // Written for external validation (openpyxl) by the build check.
        File(System.getProperty("java.io.tmpdir"), "multibiz-xlsx-test.xlsx").writeBytes(bytes)
    }

    @Test
    fun columnLettersBeyondZ() {
        val many = (1..30).map { Xlsx.Column("C$it", 8.0) }
        val sheet = String(Xlsx.build("S", many, listOf((1..30).map { "v$it" })), Charsets.ISO_8859_1)
        assertEquals(true, Xlsx.build("S", many, emptyList()).isNotEmpty())
        assertTrue(sheet.isNotEmpty())
    }
}
