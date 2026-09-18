package top.ltfan.dslutilities.test

import java.io.File
import kotlin.test.*

class GenerationEdgeCaseTest {
    @Test
    fun `private interface helpers do not affect generated members`() {
        assertEquals("ok", buildPrivateResultMembers(name = "ok").name)
        assertEquals(1, buildPrivateBuildHelper { }.value)
    }

    @OptIn(ExperimentalDslType::class)
    @Test
    fun `source opt-ins apply to generated files`() {
        val value = ExperimentalValue("ok")
        assertSame(value, buildClassOptIn(value).value)
        assertSame(value, buildFileOptIn(value).value)
        assertSame(value, buildPropertyOptIn(value).value)
    }

    @Test
    fun `escaped and reserved required names generate valid entry points`() {
        val result = buildReservedNames(`when` = "now", block = "body", builder = "factory")

        assertEquals("now", result.`when`)
        assertEquals("body", result.block)
        assertEquals("factory", result.builder)
    }

    @Test
    fun `read-only result properties override covariantly`() {
        val result = buildCovariantResult(name = "name")

        assertEquals("name", result.name)
    }

    @Test
    fun `nullable result properties override nullable Any properties`() {
        val result = buildNullableStringValue(value = null)

        assertNull(result.value)
    }

    @Test
    fun `required block names do not collide with configuration lambdas`() {
        val list = buildBlockList {
            blockChild(block = "list")
        }
        val scope = buildBlockScopeHolder {
            child(block = "scope")
        }

        assertEquals("list", list.items.single().let { it as BlockChild }.block)
        assertEquals("scope", scope.child.block)
    }

    @Test
    fun `public lists can use internal child helpers internally`() {
        val result = buildPublicInternalChildList {
            internalListChild
        }

        assertEquals("internal", (result.items.single() as InternalListChild).value)
    }

    @Test
    fun `internal child result visibility propagates through helpers and parent results`() {
        val list = buildPublicInternalResultList {
            publicInternalResultChild
        }
        val scope = buildPublicInternalResultScope {
            child()
        }

        assertEquals("internal-result", (list.items.single() as InternalChildResult).value)
        assertEquals("internal-result", scope.child.value)
    }

    @Test
    fun `diamond inheritance selects the most specific property type`() {
        val result = buildDiamondProperty(value = "specific")

        assertEquals("specific", result.value)
    }

    @Test
    fun `list children with required nested scopes are configured explicitly`() {
        val result = buildRequiredNestedList {
            requiredNestedChild {
                event { intensity = 0.25f }
            }
        }

        val child = result.items.single() as RequiredNestedChild
        assertEquals(0.25f, child.event.intensity)
    }

    @Test
    fun `backing names avoid DSL properties and child scopes`() {
        val result = buildBackingNames {
            items.add("item")
            child { intensity = 0.5f }
        }

        assertEquals("name", result.name)
        assertEquals("field", result.nameField)
        assertEquals(listOf("item"), result.items)
        assertEquals("items", result.itemsField)
        assertEquals("child", result.childField)
        assertEquals(0.5f, result.child.intensity)
    }

    @Test
    fun `validator classes with default arguments or secondary constructors are callable`() {
        val result = buildConstructorValidator()

        assertEquals("valid", result.value)
        assertEquals("valid-secondary", result.secondary)
        assertFailsWith<IllegalArgumentException> {
            buildConstructorValidator { value = "invalid" }
        }
        assertFailsWith<IllegalArgumentException> {
            buildConstructorValidator { secondary = "other" }
        }
    }

    @Test
    fun `backing names avoid inherited concrete properties`() {
        val builder = ConcreteBackingDslBuilder()

        assertEquals("default", builder.nameField)
        assertEquals("name", builder.build().name)
    }

    @Test
    fun `escaped initial values survive the generated file`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "EscapedCharInitialDslBuilder.kt" }
        assertNotNull(builder, "EscapedCharInitialDslBuilder.kt was not generated")
        val text = builder.readText()
        val escapes = listOf(
            "'\\u0000'", "'\\u0008'", "'\\u007f'", "'\\u2028'", "'\\u2029'",
            "'\\ufeff'", "'\\ue000'", "'\\u0378'", "'\\ud800'",
        )
        for (escape in escapes) {
            assertTrue(text.contains(escape), text)
        }
        assertTrue(text.contains("\"lone \\ud800 surrogate\""), text)

        val result = buildEscapedCharInitial { }
        assertEquals('\u0000', result.nul)
        assertEquals('\u0008', result.backspace)
        assertEquals('\u007f', result.delete)
        assertEquals('\u2028', result.lineSeparator)
        assertEquals('\u2029', result.paragraphSeparator)
        assertEquals('\ufeff', result.format)
        assertEquals('\ue000', result.privateUse)
        assertEquals('\u0378', result.unassigned)
        assertEquals('\uD800', result.surrogate)
        assertEquals("lone \uD800 surrogate", result.text)
    }

    @Test
    fun `floating point initial values keep their shortest literal`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "FloatingPointInitialDslBuilder.kt" }
        assertNotNull(builder, "FloatingPointInitialDslBuilder.kt was not generated")
        val text = builder.readText()
        assertTrue(text.contains("= 0.1f"), text)
        assertTrue(text.contains("= 1.0E30f"), text)

        val result = buildFloatingPointInitial { }
        assertEquals(0.1f, result.ratio)
        assertEquals(1e30f, result.huge)
        assertEquals(1e-320, result.tiny)
    }

    @Test
    fun `surrogate messages are escaped in the generated literal`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "EscapedMessageDslBuilder.kt" }
        assertNotNull(builder, "EscapedMessageDslBuilder.kt was not generated")
        val text = builder.readText()
        assertTrue(text.contains("\"value \\ud800 message\""), text)
        assertTrue(text.contains("\"list \\ud800 message\""), text)

        val result = buildEscapedMessage(value = "ok") { items.add("ok") }
        assertEquals("ok", result.value)
        assertEquals(listOf("ok"), result.items)
    }

    @Test
    fun `inner classes of generic outer classes keep their enclosing type`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "GenericOuterInnerDslBuilder.kt" }
        assertNotNull(builder, "GenericOuterInnerDslBuilder.kt was not generated")
        val text = builder.readText()
        assertTrue(text.contains("GenericOuter<String>.GenericInner<Int>"), text)
        assertTrue(text.contains("LayeredOuter<String>.LayeredMiddle<Int>.LayeredLeaf<Long>"), text)
        assertTrue(text.contains("GappedOuter<String>.GappedMiddle.GappedLeaf<Long>"), text)

        val outer = GenericOuter<String>()
        val inner = outer.GenericInner<Int>()
        val layered = LayeredOuter<String>().LayeredMiddle<Int>().LayeredLeaf<Long>()
        val gapped = GappedOuter<String>().GappedMiddle().GappedLeaf<Long>()
        val result = buildGenericOuterInner(inner = inner, layered = layered, gapped = gapped)
        assertSame(inner, result.inner)
        assertSame(layered, result.layered)
        assertSame(gapped, result.gapped)
    }

    @Test
    fun `a member named Long keeps the minimum value literal intact`() {
        val result = buildShadowedLongInitial { }

        assertEquals(Long.MIN_VALUE, result.Long)
    }

    @Test
    fun `a result supertype satisfies its component and Any members`() {
        val result = buildSatisfiableComponent(name = "value")

        assertEquals("value", result.component1())
        assertTrue(result.toString().isNotEmpty())
    }

    @Test
    fun `concrete member extensions of a supertype are inherited`() {
        val result = buildConcreteMemberExtension(name = "value")

        assertEquals("value", result.name)
        with(result) {
            assertEquals("label", "text".label)
            assertEquals("described", "text".describe())
        }
    }

    @Test
    fun `a concrete member extension in a specification is inherited`() {
        val result = buildSpecConcreteMemberExtension {
            value = 2
            assertEquals("label", "text".label)
        }

        assertEquals(2, result.value)
    }

    @Test
    fun `covariant component declarations keep the narrowest inherited type`() {
        val result = buildCovariantComponent(name = "value")

        assertEquals("value", result.component1())
    }

    @Test
    fun `a concrete component declaration is overridden covariantly`() {
        val result = buildConcreteComponent(name = "value")

        assertEquals("value", result.component1())
    }
}
