package top.ltfan.dslutilities.test

import kotlin.test.*

class HierarchyRegressionTest {
    @Test
    fun `nullable use-site type parameters stay nullable`() {
        val nullable = buildNullableGeneric(nullableValue = null)

        assertNull(nullable.nullableValue)
    }

    @Test
    fun `concrete overrides shadow abstract ancestors`() {
        val builder = ConcreteShadowDslBuilder()

        assertEquals("inherited", builder.inheritedLabel)
        builder.build()
    }

    @Test
    fun `distinct child helpers across lists target their own list`() {
        val lists = buildSeparateLists {
            transientEvent { intensity = 0.5f }
            continuousEvent { duration = 10 }
        }

        assertEquals(1, lists.transientEvents.size)
        assertEquals(1, lists.continuousEvents.size)
    }
}
