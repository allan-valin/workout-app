package dev.allan.workoutapp

import dev.allan.workoutapp.data.transfer.CsvExport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 30/09 (Allan): "Export does not recognize pt-br characters like ç àáã". The UTF-8 BOM did
 * not fix it: Google Sheets on the phone ignores the BOM and decodes the file as Latin-1
 * (18/10, screenshots in PROGRESS Phase 43). Quick fix Allan chose: fold accents to plain ASCII
 * on export ("Abdução" → "Abducao"), no BOM. A real fix (encoding per viewer, or xlsx) stays open.
 */
class CsvFileBytesTest {

    @Test
    fun `accents are folded to ascii`() {
        val bytes = CsvExport.fileBytes("Flexão de braço,Panturrilha em pé,Rosca àáã ç,Übung\n")
        assertEquals("Flexao de braco,Panturrilha em pe,Rosca aaa c,Ubung\n", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `file is plain ascii without a byte-order mark`() {
        val bytes = CsvExport.fileBytes("a,b\n")
        assertEquals("a,b\n", bytes.toString(Charsets.US_ASCII))
        assert(bytes.all { it in 0..127 })
    }

    @Test
    fun `non-latin symbols keep their place as a question mark, never shift columns`() {
        assertEquals("a,?,b\n", CsvExport.fileBytes("a,→,b\n").toString(Charsets.UTF_8))
    }
}
