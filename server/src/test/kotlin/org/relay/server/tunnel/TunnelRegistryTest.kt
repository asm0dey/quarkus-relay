package org.relay.server.tunnel

import jakarta.websocket.Session
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TunnelRegistryTest {

    private lateinit var registry: TunnelRegistry

    @BeforeEach
    fun setup() {
        registry = TunnelRegistry()
    }

    private fun createMockSession(): Session {
        val session = mock(Session::class.java)
        `when`(session.isOpen).thenReturn(true)
        return session
    }

    private fun createTunnelConnection(subdomain: String): TunnelConnection {
        return TunnelConnection(
            subdomain = subdomain,
            session = createMockSession(),
            createdAt = Instant.now(),
            metadata = emptyMap()
        )
    }

    @Test
    fun `Register tunnel - successful registration`() {
        val connection = createTunnelConnection("test123")

        val result = registry.register("test123", connection)

        assertTrue(result)
        assertEquals(1, registry.size())
        assertEquals(connection, registry.getBySubdomain("test123"))
    }

    @Test
    fun `Register duplicate - duplicate subdomain returns false`() {
        val connection1 = createTunnelConnection("duplicate")
        val connection2 = createTunnelConnection("duplicate")

        val firstResult = registry.register("duplicate", connection1)
        val secondResult = registry.register("duplicate", connection2)

        assertTrue(firstResult)
        assertFalse(secondResult)
        assertEquals(1, registry.size())
        assertEquals(connection1, registry.getBySubdomain("duplicate"))
    }

    @Test
    fun `Unregister tunnel - removes tunnel from registry`() {
        val connection = createTunnelConnection("remove-me")
        registry.register("remove-me", connection)
        assertEquals(1, registry.size())

        val result = registry.unregister("remove-me")

        assertTrue(result)
        assertEquals(0, registry.size())
        assertNull(registry.getBySubdomain("remove-me"))
    }

    @Test
    fun `Unregister tunnel - returns false for non-existent subdomain`() {
        val result = registry.unregister("non-existent")

        assertFalse(result)
        assertEquals(0, registry.size())
    }

    @Test
    fun `Get by subdomain - retrieves correct tunnel`() {
        val connection1 = createTunnelConnection("sub1")
        val connection2 = createTunnelConnection("sub2")
        registry.register("sub1", connection1)
        registry.register("sub2", connection2)

        val retrieved1 = registry.getBySubdomain("sub1")
        val retrieved2 = registry.getBySubdomain("sub2")

        assertEquals(connection1, retrieved1)
        assertEquals(connection2, retrieved2)
    }

    @Test
    fun `Get by subdomain - returns null for non-existent`() {
        val result = registry.getBySubdomain("does-not-exist")

        assertNull(result)
    }

    @Test
    fun `Get all active - lists all active tunnels`() {
        val connection1 = createTunnelConnection("active1")
        val connection2 = createTunnelConnection("active2")
        val connection3 = createTunnelConnection("active3")
        
        registry.register("active1", connection1)
        registry.register("active2", connection2)
        registry.register("active3", connection3)

        val allActive = registry.getAllActive()

        assertEquals(3, allActive.size)
        assertTrue(allActive.contains(connection1))
        assertTrue(allActive.contains(connection2))
        assertTrue(allActive.contains(connection3))
    }

    @Test
    fun `Get all active - returns empty list when no tunnels`() {
        val allActive = registry.getAllActive()

        assertTrue(allActive.isEmpty())
    }

    @Test
    fun `Has tunnel - returns true for existing tunnel`() {
        val connection = createTunnelConnection("exists")
        registry.register("exists", connection)

        assertTrue(registry.hasTunnel("exists"))
    }

    @Test
    fun `Has tunnel - returns false for non-existing tunnel`() {
        assertFalse(registry.hasTunnel("not-exists"))
    }

    @Test
    fun `Size - returns correct count`() {
        assertEquals(0, registry.size())

        registry.register("s1", createTunnelConnection("s1"))
        assertEquals(1, registry.size())

        registry.register("s2", createTunnelConnection("s2"))
        assertEquals(2, registry.size())

        registry.unregister("s1")
        assertEquals(1, registry.size())
    }

    @Test
    fun `Clear - removes all tunnels`() {
        registry.register("c1", createTunnelConnection("c1"))
        registry.register("c2", createTunnelConnection("c2"))
        registry.register("c3", createTunnelConnection("c3"))
        assertEquals(3, registry.size())

        registry.clear()

        assertEquals(0, registry.size())
        assertTrue(registry.getAllActive().isEmpty())
    }

    @Test
    fun `Thread safety - concurrent register operations`() {
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(100)
        val successfulRegistrations = java.util.concurrent.atomic.AtomicInteger(0)

        repeat(100) { i ->
            executor.submit {
                try {
                    val connection = createTunnelConnection("concurrent-$i")
                    if (registry.register("concurrent-$i", connection)) {
                        successfulRegistrations.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(100, successfulRegistrations.get())
        assertEquals(100, registry.size())
    }

    @Test
    fun `Thread safety - concurrent register and unregister operations`() {
        val executor = Executors.newFixedThreadPool(20)
        val latch = CountDownLatch(200)

        // First, register 50 tunnels
        repeat(50) { i ->
            registry.register("thread-$i", createTunnelConnection("thread-$i"))
        }

        // Then concurrently register new ones and unregister existing ones
        repeat(100) { i ->
            executor.submit {
                try {
                    val connection = createTunnelConnection("new-thread-$i")
                    registry.register("new-thread-$i", connection)
                } finally {
                    latch.countDown()
                }
            }
        }

        repeat(100) { i ->
            executor.submit {
                try {
                    val subdomain = "thread-${i % 50}"
                    registry.unregister(subdomain)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // Size should be consistent (50 new ones + any unregistrations that failed)
        // All original 50 should be potentially unregistered, all 100 new ones registered
        val finalSize = registry.size()
        assertTrue(finalSize >= 50) // At minimum, the 100 new registrations minus 50 unregistrations
        assertTrue(finalSize <= 150) // At maximum, all 100 new plus remaining old
    }

    @Test
    fun `Thread safety - concurrent get operations during modifications`() {
        val executor = Executors.newFixedThreadPool(20)
        val latch = CountDownLatch(300)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)

        // Setup initial tunnels
        repeat(20) { i ->
            registry.register("stable-$i", createTunnelConnection("stable-$i"))
        }

        // Concurrent reads
        repeat(100) {
            executor.submit {
                repeat(10) {
                    try {
                        registry.getBySubdomain("stable-${it % 20}")
                        registry.getAllActive()
                        registry.hasTunnel("stable-${it % 20}")
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    }
                }
                latch.countDown()
            }
        }

        // Concurrent writes
        repeat(100) { i ->
            executor.submit {
                try {
                    registry.register("dynamic-$i", createTunnelConnection("dynamic-$i"))
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // Concurrent unregisters
        repeat(100) { i ->
            executor.submit {
                try {
                    registry.unregister("dynamic-$i")
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(15, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(0, errors.get(), "Concurrent operations should not throw exceptions")
    }

    @Test
    fun `Shutdown - closes all connections and clears registry`() {
        val session1 = createMockSession()
        val session2 = createMockSession()
        val connection1 = TunnelConnection("shutdown1", session1)
        val connection2 = TunnelConnection("shutdown2", session2)
        
        registry.register("shutdown1", connection1)
        registry.register("shutdown2", connection2)

        registry.shutdown()

        assertEquals(0, registry.size())
        verify(session1).close()
        verify(session2).close()
    }
}
