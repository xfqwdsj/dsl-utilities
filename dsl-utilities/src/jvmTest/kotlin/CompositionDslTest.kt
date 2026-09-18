package top.ltfan.dslutilities.test

import kotlin.test.*

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
