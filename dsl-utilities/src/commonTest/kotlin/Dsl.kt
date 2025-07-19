import top.ltfan.dslutilities.LockableValueDsl
import top.ltfan.dslutilities.ValueDsl
import kotlin.test.*

class ValueTest {
    @Test
    fun `test default hooks`() {
        val dsl = valueDsl {}
        assertEquals("1", dsl.intToStringValue)
        assertEquals("2", dsl.evenIntToStringValue)
        assertFailsWith<IllegalArgumentException> { dsl.requiredValue }
        assertEquals("DEFAULT", dsl.preparedValue)
        assertNull(dsl.optionalValue)
        assertEquals(mutableListOf("666", "666", "666", "666"), dsl.listValue)
        assertEquals(mutableListOf("1", "2", "3", "4"), dsl.replaceableListValue)
        assertEquals(mutableListOf(2, 3, 4, 0), dsl.basicList)
        assertEquals(mutableListOf(2, 3, 4, 0), dsl.replaceableList)
    }

    @Test
    fun `test legal setting`() {
        val dsl = valueDsl {
            intToStringValue = "10"
            evenIntToStringValue = "4"
            requiredValue = 6
            preparedValue = "Prepared"
            optionalValue = "Optional"
            // Accessing the listValue will trigger the beforeAccess hook
            listValue.tryBypassHooks {
                add("5") // Bypassed the beforeSet hook
            }
            replaceableListValue = mutableListOf()
            basicList.add(5)
            replaceableList = mutableListOf(5)
        }

        assertEquals("10", dsl.intToStringValue)
        assertEquals("4", dsl.evenIntToStringValue)
        assertEquals(6, dsl.requiredValue)
        assertEquals("PREPARED", dsl.preparedValue)
        assertEquals("OPTIONAL", dsl.optionalValue)
        // Accessed two times and added two "4", then replaced with "666"
        assertEquals(mutableListOf("666", "666", "666", "666", "666", "666"), dsl.listValue)
        assertEquals(mutableListOf("5", "4"), dsl.replaceableListValue)
        // Accessed two times
        assertEquals(mutableListOf(2, 3, 4, 0, 5, 0), dsl.basicList)
        assertEquals(mutableListOf(5, -1, 0), dsl.replaceableList)
    }

    @Test
    fun `test illegal setting`() {
        valueDsl {
            assertFailsWith<IllegalArgumentException> { intToStringValue = "invalid" }
            assertFailsWith<IllegalArgumentException> { evenIntToStringValue = "3" }
        }
    }

    @Test
    fun `test hooks and bypassHooks`() {
        valueDsl {
            // beforeAccess: every time the listValue is accessed, "4" is added at the beginning
            val first = listValue.toList()
            val second = listValue.toList()
            assertEquals(first.size + 1, second.size)
            // bypassHooks: when tryBypassHooks returned null, the failed callback is called
            var failedCalled = false
            val notDslList = mutableListOf("a", "b")
            notDslList.tryBypassHooks({ failedCalled = true }) { clear() }
            assertEquals(true, failedCalled)
        }
    }

    @Test
    fun `test prepared and optional default and transform`() {
        valueDsl {
            assertEquals("DEFAULT", preparedValue)
            preparedValue = "abc"
            assertEquals("ABC", preparedValue)
            optionalValue = "abc"
            assertEquals("ABC", optionalValue)
            optionalValue = null
            assertNull(optionalValue)
        }
    }

    @Test
    fun `test replaceableListValue beforeReplace`() {
        val dsl = valueDsl {}
        dsl.replaceableListValue = mutableListOf("0")
        // beforeReplace will add "5" to the list
        assertTrue(dsl.replaceableListValue.contains("5"))
    }

    @Test
    fun `test basicList and replaceableList hooks`() {
        val dsl = valueDsl {}
        // Accessed, size = 3 + 1
        val oldSize = dsl.basicList.size
        // Accessed and added
        dsl.basicList.add(10)
        // Accessed
        assertEquals(4, oldSize)
        assertEquals(7, dsl.basicList.size)
        dsl.replaceableList = mutableListOf(10)
        // Replace a list will trigger setTransform, so the value "10" is not changed
        assertEquals(mutableListOf(10, -1, 0), dsl.replaceableList)
    }

    @Test
    fun `test access transformation`() {
        val dsl = valueDsl {
            // Initial: 1, 2, 3
            // Accessed, 2, 3, 4
            // Added, 2, 3, 4, 1
            listWithAccessTransformation.add(1)
            // Accessed, 3, 4, 5, 2
            // Added, 3, 4, 5, 2, 2
            listWithAccessTransformation.add(2)
            // Accessed, 4, 5, 6, 3, 3
            // Added, 4, 5, 6, 3, 3, 3
            listWithAccessTransformation.add(3)
        }

        // Accessed, 5, 6, 7, 4, 4, 4
        assertEquals(mutableListOf(5, 6, 7, 4, 4, 4), dsl.listWithAccessTransformation)
    }
}

class LockableValueTest {
    @Test
    fun `test setting after lock`() {
        val dsl = lockableValueDsl {
            intToStringValue = "10"
            evenIntToStringValue = "4"
            requiredValue = 6
            preparedValue = "Prepared"
            optionalValue = "Optional"
            listValue.add("7")
            replaceableListValue = mutableListOf()
            basicList.add(5)
            replaceableList.add(5)
        }

        assertFailsWith<IllegalStateException> { dsl.intToStringValue = "20" }
        assertEquals(mutableListOf("1", "2", "3", "7", "4"), dsl.listValue)
        assertFailsWith<IllegalStateException> { dsl.listValue.add("0") }
        assertFailsWith<IllegalStateException> { dsl.replaceableListValue = mutableListOf("6") }
    }

    @Test
    fun `test lock prevents all mutations`() {
        val dsl = lockableValueDsl {
            intToStringValue = "1"
            evenIntToStringValue = "2"
            requiredValue = 2
            preparedValue = "abc"
            optionalValue = "def"
            listValue.add("1")
            replaceableListValue = mutableListOf("2")
            basicList.add(7)
            replaceableList.add(8)
        }

        assertFailsWith<IllegalStateException> { dsl.intToStringValue = "3" }
        assertFailsWith<IllegalStateException> { dsl.evenIntToStringValue = "4" }
        assertFailsWith<IllegalStateException> { dsl.requiredValue = 4 }
        assertFailsWith<IllegalStateException> { dsl.preparedValue = "zzz" }
        assertFailsWith<IllegalStateException> { dsl.optionalValue = "zzz" }
        assertFailsWith<IllegalStateException> { dsl.listValue.add("3") }
        assertFailsWith<IllegalStateException> { dsl.replaceableListValue = mutableListOf("4") }
        assertFailsWith<IllegalStateException> { dsl.basicList.add(9) }
        assertFailsWith<IllegalStateException> { dsl.replaceableList.add(10) }
    }

    @Test
    fun `test lockable listValue beforeAccess only on locked`() {
        val dsl = lockableValueDsl {
            // When not locked, beforeAccess is not called
            assertEquals(listValue, listValue)
        }

        // After locking, beforeAccess is called
        val beforeLockedSize = dsl.listValue.size
        val afterLockedSize = dsl.listValue.size // Accessing the list to trigger beforeAccess
        assertEquals(beforeLockedSize + 1, afterLockedSize)
    }

    @Test
    fun `test access transformation with lock`() {
        val dsl = lockableValueDsl {
            // Initial: 1, 2, 3
            // Added, 1, 2, 3, 1
            listWithAccessTransformation.add(1)
            // Added, 1, 2, 3, 1, 2
            listWithAccessTransformation.add(2)
            // Added, 1, 2, 3, 1, 2, 3
            listWithAccessTransformation.add(3)
        }

        // Accessed, 2, 3, 4, 2, 3, 4
        assertEquals(mutableListOf(2, 3, 4, 2, 3, 4), dsl.listWithAccessTransformation)
    }
}

inline fun valueDsl(block: ValueTestDsl.() -> Unit) = ValueTestDsl().apply(block)
inline fun lockableValueDsl(block: LockableValueTestDsl.() -> Unit) = LockableValueTestDsl().apply(block).build()

@ValueTestDsl.Dsl
class ValueTestDsl : ValueDsl() {
    var intToStringValue by value(
        initial = 1,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
    )

    var evenIntToStringValue by conditional(
        initial = 2,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        validateGet = { true },
        validateSet = { it % 2 == 0 },
    ) { "Value must be an even integer." }

    var requiredValue by required<Int>(
        getTransform = { it * 2 },
        setTransform = { it / 2 },
    )

    var preparedValue by prepared(
        initial = "default",
        getTransform = { it.uppercase() },
        setTransform = { it.lowercase() },
    )

    var optionalValue by optional<String>(
        getTransform = { it?.uppercase() },
        setTransform = { it?.lowercase() },
    )

    val listValue by value(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        beforeGet = {
            bypassHooks {
                get(it)
            }
            set(it, "666")
        },
        beforeSet = { index, element ->
            // Before setting "5", the hook is bypassed
            require(element != "5")
            bypassHooks {
                set(index, element)
            }
        },
        beforeRemove = {
            val element = bypassHooks {
                val element = get(it)
                removeAt(it)
                element
            }
            add(it, element)
        },
        beforeAccess = { add(0, "4") },
    )

    var replaceableListValue by value(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        beforeAccess = {
            bypassHooks {
                add("4")
            }
        },
        beforeReplace = { it += "5" },
    )

    val basicList by list(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { add(0) },
    )

    var replaceableList by replaceableList(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { add(0) },
        beforeReplace = { it += -1 },
    )

    val listWithAccessTransformation by list(
        initial = mutableListOf(1, 2, 3),
        accessTransform = {
            for (i in indices) {
                this[i] += 1 // Increment each element by 1
            }
            this
        },
    )

    @DslMarker
    annotation class Dsl
}

@LockableValueTestDsl.Dsl
class LockableValueTestDsl : LockableValueDsl() {
    var intToStringValue by value(
        initial = 1,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
    )

    var evenIntToStringValue by conditional(
        initial = 2,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        validateGet = { true },
        validateSet = { it % 2 == 0 }) { "Value must be an even integer." }

    var requiredValue by required<Int>(
        getTransform = { it * 2 },
        setTransform = { it / 2 },
    ) { "Value is required." }

    var preparedValue by prepared(
        initial = "default",
        getTransform = { it.uppercase() },
        setTransform = { it.lowercase() },
    )

    var optionalValue by optional<String>(
        getTransform = { it?.uppercase() },
        setTransform = { it?.lowercase() },
    )

    val listValue by value(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        beforeAccess = {
            if (isLocked) {
                bypassHooks {
                    add("4")
                }
            }
        },
    )

    var replaceableListValue by value(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        beforeAccess = { add("4") },
        beforeReplace = { it += "5" },
    )

    val basicList by list(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { add(0) },
    )

    var replaceableList by replaceableList(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { add(0) },
        beforeReplace = { it += -1 },
    )

    val listWithAccessTransformation by list(
        initial = mutableListOf(1, 2, 3),
        accessTransform = {
            if (!isLocked) return@list this // If not locked, return the original list
            for (i in indices) {
                bypassHooks { this[i] += 1 } // Increment each element by 1
            }
            this
        },
    )

    fun build() = this.also { lock() }

    @DslMarker
    annotation class Dsl
}
