package top.ltfan.dslutilities.test

import kotlin.test.*

class SampleDslTest {
    @Test
    fun `initial values are applied`() {
        val sample = buildSample(name = "required", title = "title")

        assertEquals("1", sample.intToString)
        assertEquals("2", sample.genericBaseToString)
        assertEquals("3", sample.reorderedToString)
        assertEquals("2", sample.evenIntToString)
        assertNull(sample.nickname)
        assertEquals("DEFAULT", sample.prepared)
        assertEquals(emptyList(), sample.numbers)
    }

    @Test
    fun `legal settings are stored`() {
        val sample = buildSample(name = "required", title = "title") {
            intToString = "10"
            genericBaseToString = "20"
            reorderedToString = "30"
            evenIntToString = "4"
            nickname = "nick"
            prepared = "Prepared"
            numbers.add(2)
            numbers.add(4)
        }

        assertEquals("10", sample.intToString)
        assertEquals("20", sample.genericBaseToString)
        assertEquals("30", sample.reorderedToString)
        assertEquals("4", sample.evenIntToString)
        assertEquals("nick", sample.nickname)
        assertEquals("PREPARED", sample.prepared)
        assertEquals(listOf(2, 4), sample.numbers)
    }

    @Test
    fun `optional values accept null after being set`() {
        val sample = buildSample(name = "required", title = "title") {
            nickname = "nick"
            assertEquals("nick", nickname)
            nickname = null
            assertNull(nickname)
        }

        assertNull(sample.nickname)
    }

    @Test
    fun `mapper transforms values on get and set`() {
        val sample = buildSample(name = "required", title = "title") {
            assertEquals("DEFAULT", prepared)
            prepared = "Prepared"
            assertEquals("PREPARED", prepared)
        }

        assertEquals("PREPARED", sample.prepared)
    }

    @Test
    fun `setter validation rejects illegal values`() {
        buildSample(name = "required", title = "title") {
            assertFailsWith<IllegalArgumentException> { evenIntToString = "3" }
        }
    }

    @Test
    fun `build validation rejects illegal required values`() {
        assertFailsWith<IllegalArgumentException> { buildSample(name = "required", title = " ") }
    }

    @Test
    fun `build validation rejects illegal list elements`() {
        assertFailsWith<IllegalArgumentException> {
            buildSample(name = "required", title = "title") {
                numbers.add(2)
                numbers.add(-1)
            }
        }
    }

    @Test
    fun `inherited members join the generated builder`() {
        val builder = InheritingDslBuilder(extra = 3)
        assertEquals(7, builder.defaulted)

        val inheriting = builder.apply {
            base = "changed"
            listStored = "item"
        }.build()

        assertEquals(3, inheriting.extra)
        assertEquals("changed", inheriting.base)
        assertEquals("item", inheriting.listStored)
    }

    @Test
    fun `inherited generic members take the type arguments`() {
        val generic = buildGenericInheriting(value = 5) {
            label = "labeled"
        }

        assertEquals(5, generic.value)
        assertEquals("labeled", generic.label)
    }

    @Test
    fun `nested specifications are referenced by qualified name`() {
        val nested = buildNested { }

        assertEquals("nested", nested.nested)
    }

    @Test
    fun `internal specifications generate internal declarations`() {
        val internal = buildInternal()

        assertEquals("hidden", internal.hidden)
    }

    @Test
    fun `null default is validated at construction`() {
        assertFailsWith<IllegalArgumentException> { buildInvalidNullable() }
    }

    @Test
    fun `valid initial passes the nullable validator`() {
        val nullable = buildNullable { nickname = "ltfan" }

        assertEquals("ltfan", nullable.nickname)
    }

    @Test
    fun `result values are locked against modification`() {
        val builder = SampleDslBuilder(name = "required", title = "title")
        builder.numbers.add(2)
        val sample = builder.build()
        builder.numbers.add(4)

        assertEquals(listOf(2), sample.numbers)
        assertFailsWith<UnsupportedOperationException> { (sample.numbers as MutableList<Int>).add(6) }
    }

    @Test
    fun `result equality follows data class semantics`() {
        val first = buildSample(name = "required", title = "title") { numbers.add(2) }
        val second = buildSample(name = "required", title = "title") { numbers.add(2) }

        assertEquals(first, second)
    }
}
