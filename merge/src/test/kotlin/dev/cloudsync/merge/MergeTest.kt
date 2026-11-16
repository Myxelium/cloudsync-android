package dev.cloudsync.merge

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeTest {
    private val merger = MergeCoordinator()

    @Test
    fun nonOverlappingLinesAreBothKept() {
        val base = "Meeting is on Monday.\nLocation is Stockholm.\n"
        val local = "Meeting is on Tuesday.\nLocation is Stockholm.\n"
        val remote = "Meeting is on Monday.\nLocation is Gothenburg.\n"
        val result = merger.merge("notes.txt", "text/plain", base.bytes(), local.bytes(), remote.bytes())
        assertTrue(result.clean, result.validationError ?: result.segments.toString())
        assertEquals("Meeting is on Tuesday.\nLocation is Gothenburg.\n", result.mergedText)
    }

    @Test
    fun independentChangesInsideOneFileAreKept() {
        val base = "alpha\nbeta\ngamma\n"
        val local = "ALPHA\nbeta\ngamma\n"
        val remote = "alpha\nbeta\nGAMMA\n"
        val result = Diff3Merger.merge(base, local, remote)
        assertEquals("ALPHA\nbeta\nGAMMA\n", result.mergedText)
    }

    @Test
    fun sameLineChangeConflicts() {
        val result = Diff3Merger.merge("Monday\n", "Tuesday\n", "Wednesday\n")
        assertFalse(result.clean)
        assertEquals(1, result.conflictCount)
        assertNull(result.mergedText)
        val conflict = result.segments.filterIsInstance<MergeSegment.Conflict>().single()
        assertEquals(listOf("Tuesday"), conflict.local)
        assertEquals(listOf("Wednesday"), conflict.remote)
    }

    @Test
    fun multipleConflictsStaySeparate() {
        val base = "a\nb\nc\n"
        val local = "A\nb\nC\n"
        val remote = "a2\nb\nc2\n"
        val result = Diff3Merger.merge(base, local, remote)
        assertEquals(2, result.conflictCount)
        val resolved = result.withChoices(listOf("A", "c2"))
        assertEquals("A\nb\nc2\n", resolved)
    }

    @Test
    fun insertionsAndDeletionsCombine() {
        val base = "a\nb\nc\n"
        val local = "a\nc\n"
        val remote = "a\nb\nc\nd\n"
        val result = Diff3Merger.merge(base, local, remote)
        assertEquals("a\nc\nd\n", result.mergedText)
    }

    @Test
    fun identicalEditsCollapse() {
        val result = Diff3Merger.merge("a\n", "b\n", "b\n")
        assertTrue(result.clean)
        assertEquals("b\n", result.mergedText)
    }

    @Test
    fun emptyFiles() {
        assertEquals("hello\n", Diff3Merger.merge("", "hello\n", "").mergedText)
        assertEquals("", Diff3Merger.merge("", "", "").mergedText)
        val bothAdded = Diff3Merger.merge("", "left\n", "right\n")
        assertFalse(bothAdded.clean)
    }

    @Test
    fun largeFileKeepsDistantEdits() {
        val baseLines = (1..5_000).map { "line $it" }
        val local = baseLines.toMutableList().also { it[9] = "local change" }
        val remote = baseLines.toMutableList().also { it[4_000] = "remote change" }
        val result = Diff3Merger.merge(
            baseLines.joinToString("\n") + "\n",
            local.joinToString("\n") + "\n",
            remote.joinToString("\n") + "\n",
        )
        assertTrue(result.clean)
        val lines = result.mergedText!!.trimEnd().split('\n')
        assertEquals("local change", lines[9])
        assertEquals("remote change", lines[4_000])
        assertEquals("line 100", lines[99])
    }

    @Test
    fun invalidEncodingIsNotMergedAsText() {
        val result = merger.merge(
            "notes.txt",
            "text/plain",
            "ok".bytes(),
            byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            "ok".bytes(),
        )
        assertTrue(result.binary)
        assertFalse(result.clean)
    }

    @Test
    fun jsonStructuralMergeWhenTheLineOverlaps() {
        val base = """{"meeting":"Monday","location":"Stockholm"}"""
        val local = """{"meeting":"Tuesday","location":"Stockholm"}"""
        val remote = """{"meeting":"Monday","location":"Gothenburg"}"""
        val result = merger.merge("event.json", "application/json", base.bytes(), local.bytes(), remote.bytes())
        assertTrue(result.clean, result.validationError)
        assertEquals("json", result.strategy)
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(result.mergedText!!)
        val obj = parsed as kotlinx.serialization.json.JsonObject
        assertEquals("Tuesday", (obj["meeting"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Gothenburg", (obj["location"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun invalidJsonIsNotAccepted() {
        val result = merger.merge("bad.json", "application/json", "{".bytes(), "{".bytes(), "}".bytes())
        assertFalse(result.clean)
    }

    @Test
    fun yamlIndependentKeysMerge() {
        val base = "meeting: Monday\nlocation: Stockholm\n"
        val local = "meeting: Tuesday\nlocation: Stockholm\n"
        val remote = "meeting: Monday\nlocation: Gothenburg\n"
        val result = merger.merge("event.yaml", "application/yaml", base.bytes(), local.bytes(), remote.bytes())
        assertTrue(result.clean, result.validationError ?: result.segments.toString())
        assertTrue(result.mergedText!!.contains("Tuesday"))
        assertTrue(result.mergedText!!.contains("Gothenburg"))
    }

    @Test
    fun invalidYamlIsNotAccepted() {
        val result = merger.merge("bad.yaml", "text/yaml", "[\n".bytes(), "[\n".bytes(), ":\n".bytes())
        assertFalse(result.clean)
    }

    @Test
    fun xmlElementsAndAttributesMerge() {
        val base = """<note meeting="Monday" location="Stockholm"/>"""
        val local = """<note meeting="Tuesday" location="Stockholm"/>"""
        val remote = """<note meeting="Monday" location="Gothenburg"/>"""
        val result = merger.merge("note.xml", "application/xml", base.bytes(), local.bytes(), remote.bytes())
        assertTrue(result.clean, result.validationError ?: result.segments.toString())
        assertTrue(result.mergedText!!.contains("Tuesday"))
        assertTrue(result.mergedText!!.contains("Gothenburg"))

        val nestedBase = "<root><meeting>Monday</meeting><location>Stockholm</location></root>"
        val nestedLocal = "<root><meeting>Tuesday</meeting><location>Stockholm</location></root>"
        val nestedRemote = "<root><meeting>Monday</meeting><location>Gothenburg</location></root>"
        val nested = merger.merge("note.xml", "application/xml", nestedBase.bytes(), nestedLocal.bytes(), nestedRemote.bytes())
        assertTrue(nested.clean, nested.validationError ?: nested.segments.toString())
        assertTrue(nested.mergedText!!.contains(">Tuesday<"))
        assertTrue(nested.mergedText!!.contains(">Gothenburg<"))
    }

    @Test
    fun invalidXmlIsNotAccepted() {
        val result = merger.merge("bad.xml", "application/xml", "<a>".bytes(), "<a></b>".bytes(), "<a></a>".bytes())
        assertFalse(result.clean)
    }

    @Test
    fun csvCellsMergeOnStableIds() {
        val base = "id,name,city\n1,Ada,Stockholm\n2,Lin,Oslo\n"
        val local = "id,name,city\n1,Ada Lovelace,Stockholm\n2,Lin,Oslo\n"
        val remote = "id,name,city\n1,Ada,Gothenburg\n2,Lin,Oslo\n"
        val result = merger.merge("people.csv", "text/csv", base.bytes(), local.bytes(), remote.bytes())
        assertTrue(result.clean, result.validationError ?: result.segments.toString())
        assertTrue(result.mergedText!!.contains("Ada Lovelace,Gothenburg") || result.mergedText!!.contains("Ada Lovelace,Gothenburg"))
        val conflict = merger.merge(
            "people.csv",
            "text/csv",
            base.bytes(),
            "id,name,city\n1,Ada,Uppsala\n2,Lin,Oslo\n".bytes(),
            "id,name,city\n1,Ada,Gothenburg\n2,Lin,Oslo\n".bytes(),
        )
        assertFalse(conflict.clean)
    }

    @Test
    fun manualResolutionDoesNotDropEitherSideUntilChosen() {
        val result = Diff3Merger.merge("same\n", "local\n", "remote\n")
        assertEquals("local\n", result.withChoices(listOf("local")))
        assertEquals("remote\n", result.withChoices(listOf("remote")))
        assertEquals("edited\n", result.withChoices(listOf("edited")))
    }

    @Test
    fun binaryDocumentsArePreservedRatherThanTextMerged() {
        val result = merger.merge("deck.pptx", "application/vnd.ms-powerpoint", "base".bytes(), "local".bytes(), "remote".bytes())
        assertTrue(result.binary)
        assertFalse(result.clean)
        assertNotNull(result.validationError)
    }

    private fun String.bytes(): ByteArray = encodeToByteArray()
}
