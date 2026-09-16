package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.test.collision.toList
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

class ConfigDslTest {
    @Test
    fun `initial values are applied and lists are empty`() {
        val config = buildConfig()

        assertEquals(8080, config.port)
        assertNull(config.host)
        assertEquals(emptyList(), config.tags)
    }

    @Test
    fun `settings are stored`() {
        val config = buildConfig {
            port = 443
            host = "localhost"
            tags.add("kotlin")
            tags = mutableListOf("dsl")
        }

        assertEquals(443, config.port)
        assertEquals("localhost", config.host)
        assertEquals(listOf("dsl"), config.tags)
    }

    @Test
    fun `builder starts from initial values`() {
        val builder = ConfigBuilder()
        assertEquals(8080, builder.port)
        builder.port = 443

        assertEquals(443, builder.build().port)
    }
}

class CompositionDslTest {
    @Test
    fun `child scopes and element functions compose nested values`() {
        val pattern = buildPattern {
            events {
                transientEvent {
                    intensity = 0.8f
                    sharpness = 1f
                }
                transientEvent
                continuousEvent { duration = 100 }
                timedEvent(at = 20) { label = "low" }
                continuousEvent
                title = "pattern"
            }
        }

        val events = pattern.events.events
        assertEquals(5, events.size)
        val transient = events[0] as TransientEvent
        assertEquals(0.8f, transient.intensity)
        assertEquals(1f, transient.sharpness)
        assertTrue(events[1] is TransientEvent)
        assertEquals(100, (events[2] as ContinuousEvent).duration)
        val timed = events[3] as TimedEvent
        assertEquals(20, timed.at)
        assertEquals("low", timed.label)
        assertEquals("pattern", pattern.events.title)
    }

    @Test
    fun `repeated child scope invocations replace the stored child`() {
        val pattern = buildPattern {
            events { }
            events {
                transientEvent { intensity = 0.5f }
            }
        }

        assertEquals(1, pattern.events.events.size)
    }

    @Test
    fun `child scope is required for the built result`() {
        assertFailsWith<IllegalArgumentException> { buildPattern() }
    }

    @Test
    fun `list validation applies to generated elements`() {
        assertFailsWith<IllegalArgumentException> {
            buildPattern {
                events {
                    timedEvent(at = -1)
                }
            }
        }
    }

    @Test
    fun `parameterized child scope passes required values to the child`() {
        val holder = buildHolder {
            event(at = 30) { label = "child" }
        }

        assertEquals(30, holder.event.at)
        assertEquals("child", holder.event.label)
    }

    @Test
    fun `generated build and element functions are inline`() {
        fun buildOrNull(): Events? {
            buildEvents {
                transientEvent { return null }
            }
            return null
        }

        assertNull(buildOrNull())
    }

    @Test
    fun `custom names replace the derived names`() {
        val palette = palette {
            background = "white"
        }

        assertEquals("white", palette.background)
    }

    @Test
    fun `generateFunction false leaves the entry point to the library`() {
        val gradient = GradientFactory.gradient {
            from = 10
            to = 20
        }

        assertEquals(10, gradient.from)
        assertEquals(20, gradient.to)
    }
}

class LambdaDslTest {
    @Test
    fun `lambda properties accept the rendered types`() {
        val dsl = buildLambda {
            plain = {}
            withReceiver = { length }
            withParameters = { x, y -> x > 0 && y.isNotEmpty() }
            marked = {}
            markedWithReceiver = { length }
            suspending = {}
        }

        assertNotNull(dsl.plain)
        assertNotNull(dsl.withReceiver)
        assertNotNull(dsl.withParameters)
        assertNotNull(dsl.marked)
        assertNotNull(dsl.markedWithReceiver)
        assertNotNull(dsl.suspending)
    }

    @Test
    fun `generated builder renders function types faithfully`() {
        val file = java.io.File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "LambdaDslBuilder.kt" }
        assertNotNull(file, "LambdaDslBuilder.kt was not generated")
        val text = file.readText()

        assertTrue(text.contains("@TypeMark"), text)
        assertTrue(text.contains("String.() -> Unit"), text)
        assertTrue(text.contains("x: Int"), text)
        assertTrue(text.contains("suspend () -> Unit"), text)
    }

    @Test
    fun `concrete supertype properties are overridden`() {
        val overriding = buildOverriding { label = "custom" }

        assertEquals("custom", overriding.label)
    }

    @Test
    fun `unprovided concrete supertype properties keep their default`() {
        val inheriting = buildInheritingDefault()

        assertEquals("default", inheriting.label)
    }

    @Test
    fun `internal shorthand properties stay internal`() {
        val shorthand = buildShorthand {
            transientEvent()
            transientEvent { intensity = 0.5f }
        }

        assertEquals(2, shorthand.items.size)
    }

    @Test
    fun `child scopes inherited from generic bases bind their receiver`() {
        val generic = buildGenericChildScope {
            child { intensity = 0.5f }
        }

        assertEquals(0.5f, generic.child.intensity)
    }

    @Test
    fun `inherited generic child return types are substituted`() {
        val generic = buildGenericUnitChildReturn {
            child { intensity = 0.75f }
        }

        assertEquals(0.75f, generic.child.intensity)
    }
}

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

class GenerationEdgeCaseTest {
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
    fun `validator classes with default arguments are callable`() {
        val result = buildConstructorValidator()

        assertEquals("valid", result.value)
        assertFailsWith<IllegalArgumentException> {
            buildConstructorValidator { value = "invalid" }
        }
    }

    @Test
    fun `backing names avoid inherited concrete properties`() {
        val builder = ConcreteBackingDslBuilder()

        assertEquals("default", builder.nameField)
        assertEquals("name", builder.build().name)
    }
}

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
        val builder = java.io.File("build/generated/ksp")
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
    fun `list container type-use annotations are preserved`() {
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val argumentBuilder = java.io.File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "CarriedArgumentAnnotationDslBuilder.kt" }
        assertNotNull(argumentBuilder, "CarriedArgumentAnnotationDslBuilder.kt was not generated")
        assertTrue(
            argumentBuilder.readText()
                .contains("Map<String, List<@MaxBytes(atMost = 6.toByte()) Int>>"),
            argumentBuilder.readText(),
        )

        val functionBuilder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val linkedBuilder = java.io.File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "LinkedAnnotationDslBuilder.kt" }
        assertNotNull(linkedBuilder, "LinkedAnnotationDslBuilder.kt was not generated")
        assertTrue(linkedBuilder.readText().contains("LinkedAnnotationOuter<Int>"), linkedBuilder.readText())

        val topBuilder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "RepeatedAnnotationDslBuilder.kt" }
        assertNotNull(builder, "RepeatedAnnotationDslBuilder.kt was not generated")
        val text = builder.readText()
        val occurrences = Regex("@RepeatTypeMark").findAll(text).count()
        assertEquals(6, occurrences, text)
    }

    @Test
    fun `annotations in nested wrappers are carried`() {
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val blockName = java.io.File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "ShadowingBlockNameDslBuilder.kt" }
        assertNotNull(blockName, "ShadowingBlockNameDslBuilder.kt was not generated")
        assertTrue(blockName.readText().contains("childField_"), blockName.readText())

        val valueName = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
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
        val builder = java.io.File("build/generated/ksp")
            .walkTopDown()
            .firstOrNull { it.name == "ShadowedValidatorDslBuilder.kt" }
        assertNotNull(builder, "ShadowedValidatorDslBuilder.kt was not generated")
        assertTrue(builder.readText().contains("as newValueRef2"), builder.readText())

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
}
