package dev.allan.workoutapp

import dev.allan.workoutapp.data.transfer.PlanTransfer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * FORTALECIMENTO fase 3 (2026-10-06): 46 reference photos from the trainer's PDF had to be
 * linked by hand, one gallery pick each. A plan file may now carry the images inline
 * (`exercises[].images[].base64`); the importer stores them as user images of the matched
 * exercise and re-importing the same file must not duplicate them.
 */
class PlanTransferImagesTest {

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val b64 = Base64.getEncoder().encodeToString(jpegBytes)

    private val file = """
        {"schema_version": 1, "plan": {"name": "T", "workouts": [{"name": "A", "exercises": [
          {"match": {"names": ["Squat"]},
           "images": [{"name": "05_squat.jpg", "base64": "$b64"}],
           "sets": [{"type": "NORMAL", "value": 8}]},
          {"match": {"names": ["Plank"]}, "sets": [{"type": "NORMAL", "value": 30, "unit": "SECS"}]}
        ]}]}}
    """.trimIndent()

    @Test
    fun `images are read per exercise and empty when absent`() {
        val parsed = PlanTransfer.parse(file) as PlanTransfer.Parsed.PlanFile
        val exercises = parsed.plan.workouts[0].exercises
        assertEquals(1, exercises[0].images.size)
        assertEquals("05_squat.jpg", exercises[0].images[0].name)
        assertEquals(b64, exercises[0].images[0].base64)
        assertTrue(exercises[1].images.isEmpty())
    }

    @Test
    fun `decode returns the bytes and null for garbage`() {
        assertArrayEquals(jpegBytes, PlanTransfer.decodeImage(b64))
        assertNull(PlanTransfer.decodeImage("not base64 !!!"))
        assertNull(PlanTransfer.decodeImage(""))
    }

    @Test
    fun `file name is content-addressed so a re-import maps to the same file`() {
        val a = PlanTransfer.imageFileName(jpegBytes)
        val b = PlanTransfer.imageFileName(jpegBytes.copyOf())
        val other = PlanTransfer.imageFileName(byteArrayOf(9, 9, 9))
        assertEquals(a, b)
        assertTrue(a != other)
        assertTrue(a.startsWith("import_") && a.endsWith(".jpg"))
    }
}
