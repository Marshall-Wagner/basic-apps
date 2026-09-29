package dev.montb.basickeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the pairing between the [Lang] enum (what the keyboard can actually do) and the
 * `<subtype>` entries in `res/xml/method.xml` (what the system is told it can do).
 *
 * These are two separate sources of truth that must agree, and they silently did not for a long
 * time: Chinese, Japanese and Korean worked in the app while the system's input-language list only
 * ever offered English and Russian, so the ROM's own language switcher could not reach them. A
 * plain reading test is enough to stop that recurring, since the failure is always "added one side
 * and forgot the other".
 */
class SubtypeParityTest {

    /** Walk up from the working directory so this works whatever Gradle sets it to. */
    private fun projectFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not find $relative from ${File("").absolutePath}")
    }

    private val methodXml: String by lazy {
        projectFile("src/main/res/xml/method.xml").readText()
    }

    private val stringsXml: String by lazy {
        projectFile("src/main/res/values/strings.xml").readText()
    }

    /** The `mode=NAME` half of each subtype's extra value, in declaration order. */
    private fun declaredModes(): List<String> =
        Regex("""mode=([A-Z_]+)""").findAll(methodXml).map { it.groupValues[1] }.toList()

    @Test
    fun everyInputModeIsDeclaredToTheSystem() {
        val declared = declaredModes().toSet()
        val missing = Lang.entries.map { it.name }.filterNot { it in declared }
        assertTrue(
            "these Lang entries have no <subtype> in method.xml, so the system cannot offer " +
                "them: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun everyDeclaredSubtypeMapsToARealInputMode() {
        // The other direction: a subtype the service cannot resolve would appear in the system
        // language list and then do nothing when chosen.
        val names = Lang.entries.map { it.name }.toSet()
        val unknown = declaredModes().filterNot { it in names }
        assertTrue("method.xml declares modes that are not Lang entries: $unknown", unknown.isEmpty())
    }

    @Test
    fun subtypesAreDeclaredExactlyOnce() {
        val modes = declaredModes()
        assertEquals("duplicate <subtype> mode= values in method.xml", modes.size, modes.toSet().size)
        assertEquals(Lang.entries.size, modes.size)
    }

    @Test
    fun everySubtypeLabelResolves() {
        // A missing string resource here is not a compile error, it surfaces as a blank row in
        // the system's language list.
        val referenced = Regex("""@string/(\w+)""").findAll(methodXml).map { it.groupValues[1] }.toSet()
        val defined = Regex("""name="(\w+)"""").findAll(stringsXml).map { it.groupValues[1] }.toSet()
        val missing = referenced - defined
        assertTrue("method.xml references undefined strings: $missing", missing.isEmpty())
    }

    @Test
    fun exactlyOneSubtypeIsAsciiCapable() {
        // Apps that need latin input (password fields) ask for an ASCII-capable subtype. Pinyin
        // and romaji take latin keystrokes but convert them, so English must be the only one
        // claiming this, otherwise such a field can land in a converting mode.
        assertEquals(
            1,
            Regex("""isAsciiCapable="true"""").findAll(methodXml).count()
        )
    }
}
