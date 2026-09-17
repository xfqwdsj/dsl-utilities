package top.ltfan.dslutilities.test

import kotlin.test.*

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
