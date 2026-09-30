package top.ltfan.dslutilities.test

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FunctionWriteDslTest {
    @Test
    fun `write functions append and replace without exposing mutable results`() {
        val builder = RecordedScopeDslBuilder(orientation = "vertical")
        builder.addItem("first")
        builder.setHeader("old")
        builder.addItem("second")
        builder.setHeader("new")

        val result = builder.build()
        assertEquals("vertical", result.orientation)
        assertEquals(listOf("first", "second"), result.items)
        assertEquals("new", result.header)

        builder.addItem("third")
        assertEquals(listOf("first", "second"), result.items)
    }

    @Test
    fun `inherited append function binds its element type`() {
        val builder = InheritedAppendDslBuilder()
        builder.append("value")
        assertEquals(listOf("value"), builder.build().entries)
    }

    @Test
    fun `write parameters cannot shadow generated storage`() {
        val builder = CollidingWriteParametersDslBuilder()
        val entry = mutableListOf("first")
        builder.append(entry)
        builder.setValue("stored")

        val result = builder.build()
        assertEquals(listOf(entry), result.items)
        assertEquals("stored", result.value)
        assertEquals(listOf("first"), entry)
    }

    @Test
    fun `generated builder keeps write storage private`() {
        val file = File("build/generated/ksp").walkTopDown().firstOrNull { it.name == "RecordedScopeDslBuilder.kt" }
        assertNotNull(file)
        val text = file.readText()
        assertTrue(text.contains("private val itemsField"), text)
        assertTrue(text.contains("private var headerField"), text)
        assertTrue(text.contains("override fun addItem"), text)
        assertTrue(text.contains("override fun setHeader"), text)
        assertTrue(text.contains("val items: List<String>"), text)
    }
}
