package top.ltfan.dslutilities.test

import java.io.File
import kotlin.test.*

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
        val file = File("build/generated/ksp")
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
