import top.ltfan.dslutilities.LockableValueDsl
import top.ltfan.dslutilities.ValueDsl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ValueTest {
    @Test
    fun `all default`() {
        val dsl = valueDsl {}
        assertEquals("1", dsl.intToStringValue)
        assertEquals("2", dsl.evenIntToStringValue)
        assertFailsWith<IllegalArgumentException> { dsl.requiredValue }
        assertEquals("DEFAULT", dsl.preparedValue)
        assertNull(dsl.optionalValue)
        assertEquals(mutableListOf("1", "2", "3", "4"), dsl.listValue)
        assertEquals(mutableListOf("1", "2", "3", "4"), dsl.replaceableListValue)
        assertEquals(mutableListOf(2, 3, 4, 0), dsl.basicList)
        assertEquals(mutableListOf(2, 3, 4, 0), dsl.replaceableList)
    }

    @Test
    fun `legal set`() {
        val dsl = valueDsl {
            intToStringValue = "10"
            evenIntToStringValue = "4"
            requiredValue = 6
            preparedValue = "Prepared"
            optionalValue = "Optional"
            listValue.add("5")
            replaceableListValue = mutableListOf()
            basicList.add(5)
            replaceableList.add(5)
        }

        assertEquals("10", dsl.intToStringValue)
        assertEquals("4", dsl.evenIntToStringValue)
        assertEquals(6, dsl.requiredValue)
        assertEquals("PREPARED", dsl.preparedValue)
        assertEquals("OPTIONAL", dsl.optionalValue)
        assertEquals(mutableListOf("1", "2", "3", "4", "5", "4"), dsl.listValue)
        assertEquals(mutableListOf("5", "4"), dsl.replaceableListValue)
        assertEquals(mutableListOf(2, 3, 4, 0, 5, 0), dsl.basicList)
        assertEquals(mutableListOf(2, 3, 4, 0, 5, 0), dsl.replaceableList)
    }

    @Test
    fun `illegal set`() {
        valueDsl {
            assertFailsWith<IllegalArgumentException> { intToStringValue = "invalid" }
            assertFailsWith<IllegalArgumentException> { evenIntToStringValue = "3" }
        }
    }
}

class LockableValueTest {
    @Test
    fun `set after lock`() {
        val dsl = lockableValueDsl {
            intToStringValue = "10"
            evenIntToStringValue = "4"
            requiredValue = 6
            preparedValue = "Prepared"
            optionalValue = "Optional"
            listValue.add("5")
            replaceableListValue = mutableListOf()
            basicList.add(5)
            replaceableList.add(5)
        }

        assertFailsWith<IllegalStateException> { dsl.intToStringValue = "20" }
        assertEquals(mutableListOf("1", "2", "3", "4", "5", "4"), dsl.listValue)
        assertFailsWith<IllegalStateException> { dsl.listValue.add("0") }
        assertFailsWith<IllegalStateException> { dsl.replaceableListValue = mutableListOf("6") }
    }
}

inline fun valueDsl(block: ValueTestDsl.() -> Unit) = ValueTestDsl().apply(block)
inline fun lockableValueDsl(block: LockableValueTestDsl.() -> Unit) = LockableValueTestDsl().apply(block).build()

@ValueTestDsl.Dsl
class ValueTestDsl : ValueDsl() {
    var intToStringValue by value(
        defaultValue = 1,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
    )

    var evenIntToStringValue by conditional(
        defaultValue = 2,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        validateGet = { true },
        validateSet = { it % 2 == 0 }) { "Value must be an even integer." }

    var requiredValue by required<Int>(
        getTransform = { it * 2 },
        setTransform = { it / 2 },
        messageBuilder = { "Value is required." })

    var preparedValue by prepared(
        defaultValue = "default",
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
        beforeAccess = { it += "4" },
    )

    var replaceableListValue by value(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        beforeAccess = { it += "4" },
        beforeReplace = { it += "5" },
    )

    val basicList by list(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { it += 0 },
    )

    var replaceableList by replaceableList(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { it += 0 },
        beforeReplace = { it += -1 },
    )

    @DslMarker
    annotation class Dsl
}

@LockableValueTestDsl.Dsl
class LockableValueTestDsl : LockableValueDsl() {
    var intToStringValue by value(
        defaultValue = 1,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
    )

    var evenIntToStringValue by conditional(
        defaultValue = 2,
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        validateGet = { true },
        validateSet = { it % 2 == 0 }) { "Value must be an even integer." }

    var requiredValue by required<Int>(
        getTransform = { it * 2 },
        setTransform = { it / 2 },
        messageBuilder = { "Value is required." })

    var preparedValue by prepared(
        defaultValue = "default",
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
        beforeAccess = { it += "4" },
    )

    var replaceableListValue by value(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it.toString() },
        setTransform = { it.toInt() },
        beforeAccess = { it += "4" },
        beforeReplace = { it += "5" },
    )

    val basicList by list(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { it += 0 },
    )

    var replaceableList by replaceableList(
        initial = mutableListOf(1, 2, 3),
        getTransform = { it + 1 },
        setTransform = { it - 1 },
        beforeAccess = { it += 0 },
        beforeReplace = { it += -1 },
    )

    fun build() = this.also { lock() }

    @DslMarker
    annotation class Dsl
}
