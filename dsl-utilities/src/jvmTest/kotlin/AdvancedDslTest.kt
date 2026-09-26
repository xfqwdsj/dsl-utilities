package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@DslBuilder
interface RequiredValuesDsl {
    @DslValue(required = true)
    var title: String

    @DslValue(required = true)
    var optional: String?
}

object CompleteRequiredValues : DslBuildHook<HookedValuesDsl> {
    var calls = 0

    override fun beforeBuild(scope: HookedValuesDsl) {
        calls++
        scope.title = "from hook"
        scope.count = 2
    }
}

@DslBuilder(buildHook = CompleteRequiredValues::class)
interface HookedValuesDsl {
    @DslValue(required = true)
    var title: String

    @DslValue(required = true, validator = PositiveIntValidator::class)
    var count: Int
}

interface SharedLeaf {
    val value: Int
}

@DslBuilder(supertype = SharedLeaf::class, resultName = "SharedLeafValue", requireConfiguration = true)
interface SharedLeafDsl {
    @DslValue(initial = "0")
    var value: Int
}

@DslBuilder
interface TwoListsDsl {
    @DslList(scopeName = "intensity", children = [SharedLeafDsl::class])
    var intensities: MutableList<SharedLeaf>

    @DslList(scopeName = "sharpness", children = [SharedLeafDsl::class])
    var sharpnesses: MutableList<SharedLeaf>
}

fun DslListScope<SharedLeaf>.append(value: Int) {
    elements.add(buildSharedLeaf { this.value = value })
}

class AdvancedDslTest {
    @Test
    fun requiredNullableTracksAssignmentSeparatelyFromValue() {
        val builder = RequiredValuesDslBuilder()
        assertFailsWith<IllegalStateException> { builder.title }
        assertFailsWith<IllegalStateException> { builder.optional }
        builder.title = "set"
        assertFailsWith<IllegalStateException> { builder.build() }
        builder.optional = null
        assertNull(builder.optional)
        assertNull(builder.build().optional)
    }

    @Test
    fun hookRunsBeforeRequiredChecksAndSnapshot() {
        val before = CompleteRequiredValues.calls
        val result = buildHookedValues()
        assertEquals(before + 1, CompleteRequiredValues.calls)
        assertEquals("from hook", result.title)
        assertEquals(2, result.count)
    }

    @Test
    fun namedListsIsolateChildrenAndAppendAcrossBlocks() {
        val result = buildTwoLists {
            intensity {
                sharedLeaf { value = 1 }
                append(2)
            }
            sharpness { sharedLeaf { value = 3 } }
            intensity { sharedLeaf { value = 4 } }
        }
        assertEquals(listOf(1, 2, 4), result.intensities.map { it.value })
        assertEquals(listOf(3), result.sharpnesses.map { it.value })
    }
}
