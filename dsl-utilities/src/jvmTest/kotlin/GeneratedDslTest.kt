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
}
