package org.relay.server.tunnel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.relay.server.config.RelayConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SubdomainGeneratorTest {

    private lateinit var relayConfig: RelayConfig
    private lateinit var generator: SubdomainGenerator

    @BeforeEach
    fun setup() {
        relayConfig = mock(RelayConfig::class.java)
        `when`(relayConfig.subdomainLength()).thenReturn(12)
        generator = SubdomainGenerator(relayConfig)
    }

    @Test
    fun `Generate subdomain - produces 12 character alphanumeric string`() {
        val subdomain = generator.generate()

        assertEquals(12, subdomain.length)
        assertTrue(subdomain.matches(Regex("^[a-z0-9]+$")))
    }

    @Test
    fun `Generate subdomain - produces different values on multiple calls`() {
        val subdomains = (1..100).map { generator.generate() }

        // With 100 generations, we should have some variety
        // (though collisions are possible, they're extremely unlikely with 36^12 combinations)
        val uniqueSubdomains = subdomains.toSet()
        assertTrue(uniqueSubdomains.size > 90, "Expected mostly unique subdomains, got ${uniqueSubdomains.size} unique")
    }

    @Test
    fun `Generate subdomain - only contains lowercase letters and digits`() {
        repeat(100) {
            val subdomain = generator.generate()
            assertTrue(
                subdomain.all { it.isLowerCase() || it.isDigit() },
                "Subdomain '$subdomain' contains invalid characters"
            )
            assertFalse(
                subdomain.any { it.isUpperCase() },
                "Subdomain '$subdomain' contains uppercase letters"
            )
        }
    }

    @Test
    fun `Generate unique - returns unique subdomain not in registry`() {
        val registry = TunnelRegistry()
        
        val subdomain = generator.generateUnique(registry)

        assertFalse(registry.hasTunnel(subdomain))
        assertEquals(12, subdomain.length)
        assertTrue(subdomain.matches(Regex("^[a-z0-9]+$")))
    }

    @Test
    fun `Generate unique - detects collision and generates new`() {
        val registry = TunnelRegistry()
        val existingSubdomain = generator.generate()
        registry.register(existingSubdomain, mock(TunnelConnection::class.java))

        // Mock the generator to return the existing subdomain first, then a new one
        var callCount = 0
        val mockGenerator = object : SubdomainGenerator(relayConfig) {
            override fun generate(): String {
                callCount++
                return if (callCount == 1) existingSubdomain else "newunique123"
            }
        }

        val newSubdomain = mockGenerator.generateUnique(registry)

        assertNotEquals(existingSubdomain, newSubdomain)
        assertEquals("newunique123", newSubdomain)
        assertTrue(callCount > 1, "Expected multiple generation attempts")
    }

    @Test
    fun `Generate unique - throws exception after max attempts`() {
        val registry = TunnelRegistry()
        // Pre-populate registry with many subdomains to force collisions
        repeat(150) { i ->
            registry.register("subdomain$i", mock(TunnelConnection::class.java))
        }

        // Create a generator that only produces already-existing subdomains
        var callCount = 0
        val mockGenerator = object : SubdomainGenerator(relayConfig) {
            override fun generate(): String {
                callCount++
                return "subdomain${callCount % 150}"
            }
        }

        val exception = assertThrows(IllegalStateException::class.java) {
            mockGenerator.generateUnique(registry, maxAttempts = 10)
        }

        assertTrue(exception.message!!.contains("Unable to generate unique subdomain"))
        assertTrue(exception.message!!.contains("10 attempts"))
    }

    @Test
    fun `Generate unique with custom length - generates subdomain of specified length`() {
        val registry = TunnelRegistry()

        val subdomain8 = generator.generateUnique(registry, length = 8)
        val subdomain16 = generator.generateUnique(registry, length = 16)
        val subdomain4 = generator.generateUnique(registry, length = 4)

        assertEquals(8, subdomain8.length)
        assertEquals(16, subdomain16.length)
        assertEquals(4, subdomain4.length)
    }

    @Test
    fun `Generate with custom length - generates correct length`() {
        val lengths = listOf(1, 4, 8, 12, 16, 20, 32)

        lengths.forEach { length ->
            val subdomain = generator.generate(length)
            assertEquals(length, subdomain.length, "Expected length $length but got ${subdomain.length}")
            assertTrue(subdomain.matches(Regex("^[a-z0-9]+$")))
        }
    }

    @Test
    fun `Generate with custom length - rejects zero or negative length`() {
        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(-1)
        }

        assertThrows(IllegalArgumentException::class.java) {
            generator.generate(-100)
        }
    }

    @Test
    fun `Concurrent generation - thread safety`() {
        val executor = Executors.newFixedThreadPool(20)
        val latch = CountDownLatch(1000)
        val generatedSubdomains = ConcurrentHashMap.newKeySet<String>()
        val errors = mutableListOf<Exception>()

        repeat(1000) {
            executor.submit {
                try {
                    val subdomain = generator.generate()
                    generatedSubdomains.add(subdomain)
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.isEmpty(), "Concurrent generation threw exceptions: $errors")
        // Most should be unique (allow for some extremely unlikely collisions)
        assertTrue(
            generatedSubdomains.size >= 990,
            "Expected at least 990 unique subdomains, got ${generatedSubdomains.size}"
        )
    }

    @Test
    fun `Concurrent unique generation - thread safety with collision detection`() {
        val registry = TunnelRegistry()
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(100)
        val generatedSubdomains = ConcurrentHashMap.newKeySet<String>()
        val errors = mutableListOf<Exception>()

        repeat(100) {
            executor.submit {
                try {
                    val subdomain = generator.generateUnique(registry)
                    generatedSubdomains.add(subdomain)
                    registry.register(subdomain, mock(TunnelConnection::class.java))
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(errors.isEmpty(), "Concurrent unique generation threw exceptions: $errors")
        assertEquals(100, generatedSubdomains.size, "All 100 subdomains should be unique")
        assertEquals(100, registry.size(), "All 100 subdomains should be registered")
    }

    @Test
    fun `Uses config length - respects relay configuration`() {
        `when`(relayConfig.subdomainLength()).thenReturn(8)
        val configGenerator = SubdomainGenerator(relayConfig)

        val subdomain = configGenerator.generate()

        assertEquals(8, subdomain.length)
    }

    @Test
    fun `Generate unique with length parameter overrides config`() {
        `when`(relayConfig.subdomainLength()).thenReturn(8)
        val configGenerator = SubdomainGenerator(relayConfig)
        val registry = TunnelRegistry()

        val subdomain = configGenerator.generateUnique(registry, length = 16)

        assertEquals(16, subdomain.length)
    }

    @Test
    fun `Distribution test - all characters appear in generation`() {
        // Generate many subdomains and verify we see variety in characters
        val allChars = (1..1000).flatMap { generator.generate().toList() }
        val uniqueChars = allChars.toSet()

        // Should have many different characters from the alphanumeric set
        assertTrue(uniqueChars.size >= 20, "Expected good character variety, got ${uniqueChars.size} unique chars")
        
        // All should be valid alphanumeric lowercase
        assertTrue(uniqueChars.all { it in 'a'..'z' || it in '0'..'9' })
    }
}
