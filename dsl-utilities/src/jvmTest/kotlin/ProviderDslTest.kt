package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal object PositiveValueHook : DslValueHook<Int> {
    override fun beforeSet(value: Int) {
        require(value > 0)
    }
}

internal object PositiveListHook : DslListHook<Int> {
    override fun beforeSet(element: Int) {
        require(element > 0)
    }
}

internal object NextValue : DslInitialProvider<Int> {
    private var next = 0

    override fun provide(): Int = ++next
}

@DslBuilder
internal interface HookedDslListChildDsl

@DslBuilder
internal interface ProviderDsl {
    @DslValue(provider = NextValue::class, hook = PositiveValueHook::class)
    var value: Int

    @DslList(hook = PositiveListHook::class)
    var values: MutableList<Int>
}

@Suppress("TestFunctionName")
@DslBuilder
internal interface HookedListNameCollisionDsl {
    @DslList(hook = PositiveListHook::class)
    var values: MutableList<Int>

    @DslChild
    fun HookedDslList(block: HookedDslListChildDsl.() -> Unit = {})
}

class ProviderDslTest {
    @Test
    fun `provider is called for each builder`() {
        val first = ProviderDslBuilder()
        val second = ProviderDslBuilder()

        assertEquals(first.value + 1, second.value)
        assertEquals(first.value, first.build().value)
    }

    @Test
    fun `hooks intercept property and direct list writes`() {
        val builder = ProviderDslBuilder()

        assertFailsWith<IllegalArgumentException> { builder.value = 0 }
        assertFailsWith<IllegalArgumentException> { builder.values.add(0) }
        builder.values.add(3)
        assertFailsWith<IllegalArgumentException> { builder.values = mutableListOf(4, 0) }
        assertEquals(listOf(3), builder.build().values)
    }
}
