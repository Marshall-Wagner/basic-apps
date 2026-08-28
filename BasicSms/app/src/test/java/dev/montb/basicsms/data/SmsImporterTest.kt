package dev.montb.basicsms.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for SmsImporter's NDJSON parsing (the SMS/MMS backup format). Runs against the
 * real org.json (added as a test dependency; Android's android.jar stubs it out otherwise),
 * with no Context or provider I/O, so only the pure parsing logic is exercised.
 */
class SmsImporterTest {

    private fun parse(line: String) = SmsImporter.parseLine(line, emptyMap<String, File>())

    // --- looksLikeMessage ---

    @Test
    fun looksLikeMessageAcceptsMessageObjects() {
        assertTrue(SmsImporter.looksLikeMessage("""{"address":"1","body":"hi","date":"1"}"""))
        assertTrue(SmsImporter.looksLikeMessage("""{"msg_box":"1","date":"1"}"""))
    }

    @Test
    fun looksLikeMessageRejectsNonMessages() {
        assertFalse(SmsImporter.looksLikeMessage("not json at all"))
        assertFalse(SmsImporter.looksLikeMessage("""{"foo":"bar"}"""))
    }

    // --- SMS parsing ---

    @Test
    fun parsesAnIncomingSms() {
        val e = parse("""{"address":"+15551234567","body":"Hello","date":"1700000000000","type":"1","sub_id":"2"}""")!!
        assertEquals("+15551234567", e.address)
        assertEquals("Hello", e.body)
        assertEquals(1_700_000_000_000L, e.timestamp)
        assertTrue(e.incoming)
        assertEquals(2, e.subId)
    }

    @Test
    fun typeTwoIsOutgoing() {
        val e = parse("""{"address":"1","body":"Sent","date":"1700000000000","type":"2"}""")!!
        assertFalse(e.incoming)
    }

    @Test
    fun smsMissingRequiredFieldsIsSkipped() {
        assertNull(parse("""{"address":"1","date":"1700000000000"}""")) // no body
        assertNull(parse("""{"body":"hi","date":"1700000000000"}"""))   // no address
        assertNull(parse("""{"address":"1","body":"hi"}"""))            // no date
    }

    // --- MMS parsing ---

    @Test
    fun mmsDateInSecondsIsNormalizedToMillis() {
        // A "msg_box" field routes to the MMS path; a seconds-granularity date is scaled up.
        val e = parse("""{"msg_box":"1","date":"1700000000","address":"+15551234567"}""")!!
        assertEquals(1_700_000_000_000L, e.timestamp)
        assertTrue(e.incoming)
        assertEquals("+15551234567", e.address)
    }
}
