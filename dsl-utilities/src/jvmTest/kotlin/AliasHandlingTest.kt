package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.test.collision.toList
import java.io.File
import kotlin.test.*

class AliasHandlingTest {
    @Test
    fun `function type aliases bind the child receiver`() {
        val result = buildAliasedChildScope {
            aliasedChild { intensity = 0.5f }
            aliasedUnitChild { sharpness = 1f }
        }

        assertEquals(0.5f, result.aliasedChild.intensity)
        assertEquals(1f, result.aliasedUnitChild.sharpness)
    }

    @Test
    fun `type-use annotations survive alias expansion`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "AliasedAnnotationDslBuilder.kt" }
        assertNotNull(builder, "AliasedAnnotationDslBuilder.kt was not generated")
        assertTrue(builder.readText().contains("@MaxBytes"), "the alias usage annotation was dropped")

        val result = buildAliasedAnnotation(marked = "ok")

        assertEquals("ok", result.marked)
    }

    @Test
    fun `aliased list and value properties are supported`() {
        val list = buildAliasedList {
            values.add(1)
            values.add(2)
        }
        val value = buildAliasedValue()

        assertEquals(listOf(1, 2), list.values)
        assertEquals("alias", value.value)
        assertFailsWith<IllegalArgumentException> {
            buildAliasedValue { this.value = " " }
        }
    }

    @Test
    fun `aliased supertypes contribute their members`() {
        val builder = AliasedInheritanceDslBuilder(extra = 5)
        assertEquals(7, builder.defaulted)

        val inherited = builder.apply {
            aliasOwn = "own"
        }.build()

        assertEquals(5, inherited.extra)
        assertEquals("base", inherited.base)
        assertEquals("own", inherited.aliasOwn)
    }

    @Test
    fun `same-erasure overloads with different signatures coexist`() {
        val generated: (OverloadSpecDsl.() -> Unit) -> OverloadSpec = ::buildOverloadSpec
        val direct: ((Int) -> Unit) -> Unit = ::buildOverloadSpec

        assertNotNull(generated { })
        var seen = -1
        direct { seen = it }
        assertEquals(0, seen)
    }

    @Test
    fun `child scope results override covariant supertype properties`() {
        val container = buildEventContainer {
            child { intensity = 0.25f }
        }

        assertEquals(0.25f, container.child.intensity)
    }

    @Test
    fun `generic function type aliases bind the child receiver`() {
        val result = buildGenericAliasedChildScope {
            genericAliasedChild { intensity = 0.75f }
        }

        assertEquals(0.75f, result.genericAliasedChild.intensity)
    }

    @Test
    fun `child scope results override Any supertype properties`() {
        val holder = buildAnyChildHolder {
            child { value = "changed" }
        }

        assertEquals("changed", holder.child.value)
    }

    @Test
    fun `nullable block overloads coexist with generated functions`() {
        val generated: (NullableBlockSpecDsl.() -> Unit) -> NullableBlockSpec = ::buildNullableBlockSpec
        val direct: ((NullableBlockSpecDsl.() -> Unit)?) -> Unit = ::buildNullableBlockSpec

        assertNotNull(generated { })
        direct(null)
    }

    @Test
    fun `required-suffix overloads keep the generated function callable`() {
        val generated: (String, RequiredSuffixDsl.() -> Unit) -> RequiredSuffix = ::buildRequiredSuffix

        assertEquals("id", generated("id") { }.id)
        buildRequiredSuffix("id", true)
    }

    @Test
    fun `vararg-suffix overloads keep the generated function callable`() {
        val generated: (String, VarargSuffixDsl.() -> Unit) -> VarargSuffix = ::buildVarargSuffix

        assertEquals("id", generated("id") { }.id)
        buildVarargSuffix("id", true)
    }

    @Test
    fun `list container type-use annotations are preserved`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "AliasedMarkedIntsDslBuilder.kt" }
        assertNotNull(builder, "AliasedMarkedIntsDslBuilder.kt was not generated")
        val text = builder.readText()
        assertTrue(
            text.contains("override var values: @MaxBytes(atMost = 5.toByte()) MutableList<Int>"),
            text,
        )
        assertTrue(text.contains("public val values: List<Int>"), text)

        val result = buildAliasedMarkedInts {
            values.add(1)
        }

        assertEquals(listOf(1), result.values)
    }

    @Test
    fun `aliased Any supertype properties accept child scope results`() {
        val holder = buildAliasAnyHolder {
            child { value = "aliased" }
        }

        assertEquals("aliased", holder.child.value)
    }

    @Test
    fun `bare type parameter child blocks bind after substitution`() {
        val result = buildBareChildParameter {
            child { intensity = 0.6f }
        }

        assertEquals(0.6f, result.child.intensity)
    }

    @Test
    fun `bare type parameter child blocks accept function type aliases`() {
        val result = buildAliasedBareChildParameter {
            child { intensity = 0.65f }
        }

        assertEquals(0.65f, result.child.intensity)
    }

    @Test
    fun `aliased nullable star list values default to null`() {
        val result = buildAliasedStarList()

        assertNull(result.values)
    }

    @Test
    fun `nullable aliased Any supertype properties accept child scope results`() {
        val holder = buildNullableAliasAnyHolder {
            child { value = "nullable-any" }
        }

        assertEquals("nullable-any", holder.child.value)
    }

    @Test
    fun `star projections on unused alias parameters are supported`() {
        val result = buildPhantomChildScope {
            child { value = "phantom" }
        }

        assertEquals("phantom", result.child.value)
    }

    @Test
    fun `star aliases that expand to Any accept child scope results`() {
        val holder = buildGenericAliasAnyHolder {
            child { value = "generic-any" }
        }

        assertEquals("generic-any", holder.child.value)
    }

    @Test
    fun `private aliases expand to their underlying type`() {
        val result = buildPrivateAlias(value = "private")

        assertEquals("private", result.value)
    }

    @Test
    fun `aliased child blocks inherited from generic bases bind their receiver`() {
        val result = buildAliasedParamChildScope {
            aliasedParamChild { intensity = 0.7f }
        }

        assertEquals(0.7f, result.aliasedParamChild.intensity)
    }

    @Test
    fun `aliased supertypes of generic middle bases contribute their members`() {
        val result = buildAliasedValueMid(value = "inherited")

        assertEquals("inherited", result.value)
        assertEquals("own", result.own)
    }

    @Test
    fun `mappers inherited through aliased generic bases are resolved`() {
        val initial = buildAliasedMapper()
        val changed = buildAliasedMapper { value = "9" }

        assertEquals("7", initial.value)
        assertEquals("9", changed.value)
    }

    @Test
    fun `type-use annotations on type arguments are preserved`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "GenericArgumentAnnotationDslBuilder.kt" }
        assertNotNull(builder, "GenericArgumentAnnotationDslBuilder.kt was not generated")
        val text = builder.readText()
        assertTrue(text.contains("List<@MaxBytes(atMost = 3.toByte()) String>?"), text)
        assertTrue(text.contains("Map<String, List<@MaxBytes(atMost = 4.toByte()) Int>>"), text)

        val result = buildGenericArgumentAnnotation(
            value = listOf("a"),
            nested = mapOf("key" to listOf(1)),
        )

        assertEquals(listOf("a"), result.value)
        assertEquals(mapOf("key" to listOf(1)), result.nested)
    }

    @Test
    fun `annotations in alias targets are carried onto substituted arguments`() {
        val argumentBuilder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "CarriedArgumentAnnotationDslBuilder.kt" }
        assertNotNull(argumentBuilder, "CarriedArgumentAnnotationDslBuilder.kt was not generated")
        assertTrue(
            argumentBuilder.readText()
                .contains("Map<String, List<@MaxBytes(atMost = 6.toByte()) Int>>"),
            argumentBuilder.readText(),
        )

        val functionBuilder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "CarriedFunctionAnnotationDslBuilder.kt" }
        assertNotNull(functionBuilder, "CarriedFunctionAnnotationDslBuilder.kt was not generated")
        assertTrue(
            functionBuilder.readText().contains("(@MaxBytes(atMost = 7.toByte()) String) -> Unit"),
            functionBuilder.readText(),
        )

        val result = buildCarriedArgumentAnnotation(value = mapOf("key" to listOf(1)))
        val callbackResult = buildCarriedFunctionAnnotation(callback = { })

        assertEquals(mapOf("key" to listOf(1)), result.value)
        assertNotNull(callbackResult.callback)
    }

    @Test
    fun `kept aliases do not duplicate target annotations`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "MarkedKeepDslBuilder.kt" }
        assertNotNull(builder, "MarkedKeepDslBuilder.kt was not generated")
        val text = builder.readText()
        assertTrue(text.contains("MarkedKeepAlias<*>"), text)
        assertFalse(text.contains("@MaxBytes(atMost = 40.toByte()) MarkedKeepAlias"), text)

        val result = buildMarkedKeep(items = mutableListOf<Any>())

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `annotations in nested alias targets are carried`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "WrappedDslBuilder.kt" }
        assertNotNull(builder, "WrappedDslBuilder.kt was not generated")
        assertTrue(
            builder.readText().contains("List<List<@MaxBytes(atMost = 20.toByte()) Int>>"),
            builder.readText(),
        )

        val result = buildWrapped(value = listOf(listOf(1)))

        assertEquals(listOf(listOf(1)), result.value)
    }

    @Test
    fun `annotations on intermediate alias links render the alias by name`() {
        val linkedBuilder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "LinkedAnnotationDslBuilder.kt" }
        assertNotNull(linkedBuilder, "LinkedAnnotationDslBuilder.kt was not generated")
        assertTrue(linkedBuilder.readText().contains("LinkedAnnotationOuter<Int>"), linkedBuilder.readText())

        val topBuilder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "TopTargetAnnotationDslBuilder.kt" }
        assertNotNull(topBuilder, "TopTargetAnnotationDslBuilder.kt was not generated")
        assertTrue(topBuilder.readText().contains("TopTargetAnnotationLink<Int>"), topBuilder.readText())

        val linked = buildLinkedAnnotation(value = listOf(1))
        val top = buildTopTargetAnnotation(value = listOf(2))

        assertEquals(listOf(1), linked.value)
        assertEquals(listOf(2), top.value)
    }

    @Test
    fun `repeated annotations of one source stay repeated`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "RepeatedAnnotationDslBuilder.kt" }
        assertNotNull(builder, "RepeatedAnnotationDslBuilder.kt was not generated")
        val text = builder.readText()
        val occurrences = Regex("@RepeatTypeMark").findAll(text).count()
        assertEquals(6, occurrences, text)
    }

    @Test
    fun `annotations in nested wrappers are carried`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "NestedWrappedDslBuilder.kt" }
        assertNotNull(builder, "NestedWrappedDslBuilder.kt was not generated")
        val text = builder.readText()
        assertTrue(text.contains("Map<String, List<@MaxBytes(atMost = 20.toByte()) Int>>"), text)
        assertTrue(text.contains("List<List<List<@MaxBytes(atMost = 20.toByte()) Int>>>"), text)

        val result = buildNestedWrapped(
            map = mapOf("key" to listOf(1)),
            triple = listOf(listOf(listOf(2))),
        )

        assertEquals(mapOf("key" to listOf(1)), result.map)
        assertEquals(listOf(listOf(listOf(2))), result.triple)
    }

    @Test
    fun `annotations on inherited generic members are carried`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "InheritedAnnotationDslBuilder.kt" }
        assertNotNull(builder, "InheritedAnnotationDslBuilder.kt was not generated")
        assertTrue(
            builder.readText().contains("List<@MaxBytes(atMost = 95.toByte()) String>"),
            builder.readText(),
        )

        val result = buildInheritedAnnotation(annotated = listOf("a"))

        assertEquals(listOf("a"), result.annotated)
    }

    @Test
    fun `annotations on inherited child scope parameters are carried`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "AnnotatedParamScopeDslBuilder.kt" }
        assertNotNull(builder, "AnnotatedParamScopeDslBuilder.kt was not generated")
        assertTrue(
            builder.readText().contains("List<@MaxBytes(atMost = 90.toByte()) String>"),
            builder.readText(),
        )

        val result = buildAnnotatedParamScope {
            child(values = listOf("a")) { }
        }

        assertEquals(listOf("a"), result.child.values)
    }

    @Test
    fun `annotations on child block aliases are carried`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "AnnotatedBlockScopeDslBuilder.kt" }
        assertNotNull(builder, "AnnotatedBlockScopeDslBuilder.kt was not generated")
        assertTrue(
            builder.readText().contains("@MaxBytes(atMost = 91.toByte()) AnnotatedParamChildDsl.() -> Unit"),
            builder.readText(),
        )

        val result = buildAnnotatedBlockScope {
            child(values = listOf("b")) { }
        }

        assertEquals(listOf("b"), result.child.values)
    }

    @Test
    fun `generic alias child blocks keep the receiver style`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "GenericAliasedChildScopeDslBuilder.kt" }
        assertNotNull(builder, "GenericAliasedChildScopeDslBuilder.kt was not generated")
        assertTrue(
            builder.readText().contains("block: TransientEventDsl.() -> Unit"),
            builder.readText(),
        )
    }

    @Test
    fun `generic alias child blocks carry their annotations`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "AnnotatedGenericChildScopeDslBuilder.kt" }
        assertNotNull(builder, "AnnotatedGenericChildScopeDslBuilder.kt was not generated")
        assertTrue(
            builder.readText().contains("@MaxBytes(atMost = 92.toByte()) TransientEventDsl.() -> Unit"),
            builder.readText(),
        )

        val result = buildAnnotatedGenericChildScope {
            child { intensity = 0.9f }
        }

        assertEquals(0.9f, result.child.intensity)
    }

    @Test
    fun `required function type properties are noinline in generated entry points`() {
        var seen: String? = null
        val result = buildFunctionRequired(callback = { seen = it })

        result.callback("called")
        assertEquals("called", seen)
        assertEquals("x", result.value)

        val nullable = buildNullableFunctionRequired(callback = null)
        assertNull(nullable.callback)

        val holder = buildFunctionListHolder {
            functionChild(callback = { })
        }
        assertEquals(1, holder.items.size)
        assertNotNull((holder.items.single() as FunctionChild).callback)
    }

    @Test
    fun `child scope parameters do not shadow the generated backing field`() {
        val blockName = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "ShadowingBlockNameDslBuilder.kt" }
        assertNotNull(blockName, "ShadowingBlockNameDslBuilder.kt was not generated")
        assertTrue(blockName.readText().contains("childField_"), blockName.readText())

        val valueName = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "ShadowingValueNameDslBuilder.kt" }
        assertNotNull(valueName, "ShadowingValueNameDslBuilder.kt was not generated")
        assertTrue(valueName.readText().contains("childField_"), valueName.readText())

        val block = buildShadowingBlockName {
            child { }
        }
        assertNotNull(block.child)

        val value = buildShadowingValueName {
            child(childField = 3) { }
        }
        assertEquals(3, value.child.childField)
    }

    @Test
    fun `list properties are not shadowed by element function parameters`() {
        val caller = mutableListOf<Any>()
        val holder = buildShadowedListHolder {
            shadowedListChild(items = caller) { }
        }
        assertEquals(0, caller.size)
        assertEquals(1, holder.items.size)

        val blockHolder = buildBlockNamedListHolder {
            blockNamedListChild { }
        }
        assertEquals(1, blockHolder.block.size)
    }

    @Test
    fun `entry block runs when a child scope shares its name`() {
        val result = buildEntryBlockCapture {
            block { touched = true }
        }
        assertTrue(result.block.touched)
    }

    @Test
    fun `lowercase validator names are referenced safely`() {
        val result = buildLowercaseValidator { value = 2 }
        assertEquals(2, result.value)

        val shadowed = buildShadowedValidator { value = 3 }
        assertEquals(3, shadowed.value)

        assertFailsWith<IllegalArgumentException> { buildLowercaseValidator { value = -1 } }
    }

    @Test
    fun `lowercase specification names generate builders`() {
        val result = buildlowercaseSpec { value = 7 }
        assertEquals(7, result.value)
    }

    @Test
    fun `keyword validator names are referenced safely`() {
        val result = buildKeywordValidator { value = 8 }
        assertEquals(8, result.value)

        assertFailsWith<IllegalArgumentException> { buildKeywordValidator { value = -1 } }
    }

    @Test
    fun `standard library calls are not captured by spec members`() {
        val requireResult = buildShadowedRequire { value = 1 }
        assertEquals(1, requireResult.value)
        assertFailsWith<IllegalArgumentException> { buildShadowedRequire { value = -1 } }

        assertFailsWith<IllegalArgumentException> { buildShadowedRequireNotNull { } }
        val requireNotNullResult = buildShadowedRequireNotNull { child { } }
        assertNotNull(requireNotNullResult.child)

        val listResult = buildShadowedList { items = mutableListOf(1, 2, 3) }
        assertEquals(listOf(1, 2, 3), listResult.items)
    }

    @Test
    fun `standard library calls bind their import when the package declares one`() {
        val result = buildLowercaseValidator { value = 7 }
        assertEquals(7, result.value)

        assertFailsWith<IllegalArgumentException> { buildLowercaseValidator { value = -1 } }
    }

    @Test
    fun `child members named apply do not block the child scope`() {
        val result = buildHarmlessApplyParent {
            child(apply = 1) { }
        }
        assertEquals(1, result.child.apply)
    }

    @Test
    fun `specifications nested in lowercase containers render nested references`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "nestedSpecDslBuilder.kt" }
        assertNotNull(builder, "nestedSpecDslBuilder.kt was not generated")
        assertTrue(builder.readText().contains(": nestedContainer.nestedSpecDsl"), builder.readText())

        val result = buildnestedSpec { nested = "value" }
        assertEquals("value", result.nested)
    }

    @Test
    fun `mapper classes named build are referenced safely`() {
        val result = buildBuildMapperName { value = "3" }
        assertEquals("3", result.value)
    }

    @Test
    fun `nested validator containers shadowed by locals are aliased`() {
        val result = buildNestedValidatorShadow { values = mutableListOf(1, 2) }
        assertEquals(listOf(1, 2), result.values)

        assertFailsWith<IllegalArgumentException> {
            buildNestedValidatorShadow { values = mutableListOf(-1) }
        }
    }

    @Test
    fun `aliases avoid declarations in the same package`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "ShadowedValidatorDslBuilder.kt" }
        assertNotNull(builder, "ShadowedValidatorDslBuilder.kt was not generated")
        assertTrue(builder.readText().contains("as newValueRef__"), builder.readText())

        val result = buildShadowedValidator { value = 6 }
        assertEquals(6, result.value)
    }

    @Test
    fun `standard library toList stays callable beside a type of the same name`() {
        val result = buildToListTypeCapture(other = toList()) {
            items = mutableListOf(1, 2)
        }
        assertEquals(listOf(1, 2), result.items)
    }

    @Test
    fun `child builder constructors shadowed by members are aliased`() {
        val result = buildCaptureChildBuilder {
            child { value = 1 }
            NamedChildDslBuilder { value = 2 }
        }
        assertEquals(1, result.child.value)
        assertEquals(2, result.NamedChildDslBuilder.value)
    }

    @Test
    fun `multiline initial values are rendered as string literals`() {
        val result = buildMultilineInitial { }
        assertEquals("first\nsecond", result.text)
    }

    @Test
    fun `negative boundary initial values are rendered as literals`() {
        val result = buildNegativeBoundaryInitial { }
        assertEquals(Short.MIN_VALUE, result.short)
        assertEquals(Byte.MIN_VALUE, result.byte)
        assertEquals(Long.MIN_VALUE, result.long)
    }

    @Test
    fun `an explicit empty initial value is an empty string`() {
        val result = buildEmptyInitial { }
        assertEquals("", result.text)
        assertEquals("", result.nullable)
    }

    @Test
    fun `a user annotation named like a compiler marker is not a marker`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "MarkerNameCollisionDslBuilder.kt" }
        assertNotNull(builder, "MarkerNameCollisionDslBuilder.kt was not generated")
        assertFalse(builder.readText().contains("String.() -> Unit"), builder.readText())

        val result = buildMarkerNameCollision(callback = { })
        assertNotNull(result.callback)
    }

    @Test
    fun `a user annotation named like a parameter marker is not a marker`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "ParameterNameCollisionDslBuilder.kt" }
        assertNotNull(builder, "ParameterNameCollisionDslBuilder.kt was not generated")
        assertFalse(builder.readText().contains("renamed:"), builder.readText())

        val result = buildParameterNameCollision(callback = { })
        assertNotNull(result.callback)
    }

    @Test
    fun `annotated nested function receivers keep their parentheses`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "NestedFunctionReceiverDslBuilder.kt" }
        assertNotNull(builder, "NestedFunctionReceiverDslBuilder.kt was not generated")
        assertTrue(
            builder.readText()
                .contains("(@MaxBytes(atMost = 93.toByte()) PlainChildDsl.() -> Unit).() -> Unit"),
            builder.readText(),
        )

        val result = buildNestedFunctionReceiver(callback = { })
        assertNotNull(result.callback)
    }

    @Test
    fun `nullable nested function receivers keep their parentheses`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "NullableNestedFunctionReceiverDslBuilder.kt" }
        assertNotNull(builder, "NullableNestedFunctionReceiverDslBuilder.kt was not generated")
        assertTrue(
            builder.readText().contains("(PlainChildDsl.() -> Unit)?.() -> Unit"),
            builder.readText(),
        )

        val result = buildNullableNestedFunctionReceiver(callback = { })
        assertNotNull(result.callback)
    }

    @Test
    fun `aliases with nullable targets are not marked nullable again`() {
        val builder = File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "AliasedStarListDslBuilder.kt" }
        assertNotNull(builder, "AliasedStarListDslBuilder.kt was not generated")
        assertTrue(builder.readText().contains("StarListAlias<*>"), builder.readText())
        assertFalse(builder.readText().contains("StarListAlias<*>?"), builder.readText())

        val result = buildAliasedStarList { values = listOf(1) }
        assertEquals(listOf(1), result.values)
    }
}
