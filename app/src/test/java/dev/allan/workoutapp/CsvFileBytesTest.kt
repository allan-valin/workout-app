package dev.allan.workoutapp

import dev.allan.workoutapp.data.transfer.CsvExport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 30/09 (Allan): "Export does not recognize pt-br characters like ç àáã". The CSV was
 * written as bare UTF-8; spreadsheet apps on the phone and Excel default to the system
 * code page unless the file starts with a UTF-8 byte-order mark.
 */
class CsvFileBytesTest {

    @Test
    fun `csv file bytes start with the UTF-8 BOM`() {
        val bytes = CsvExport.fileBytes("a,b\n")
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
    }

    @Test
    fun `accented names survive the round trip`() {
        val csv = "Flexão de braço,Panturrilha em pé,Rosca àáã ç\n"
        val bytes = CsvExport.fileBytes(csv)
        assertEquals(csv, bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8))
    }
}
