package com.github.senocak.etcd

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.client.EtcdException
import com.github.senocak.etcd.client.EtcdWatchStream
import com.github.senocak.etcd.model.WatchEvent
import com.github.senocak.etcd.model.WatchEventType
import com.github.senocak.etcd.settings.EtcdConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the client against a real etcd.
 *
 * Skipped unless an endpoint is supplied, so ordinary builds stay offline:
 *
 *     ./gradlew test -Detcd.it.url=http://localhost:2379
 *
 * Everything is written under [PREFIX] and removed afterwards, so pointing this at a scratch
 * cluster is safe. Read-only cluster calls are included; nothing here reconfigures membership.
 */
class EtcdClientIntegrationTest {

    private lateinit var client: EtcdClient

    @Before
    fun connect() {
        val url = System.getProperty("etcd.it.url").orEmpty()
        assumeTrue("set -Detcd.it.url to run integration tests", url.isNotBlank())
        client = EtcdClient(EtcdConnection("integration-test", url)) { null }
        client.kv.deletePrefix(PREFIX)
    }

    @After
    fun cleanUp() {
        if (::client.isInitialized) {
            runCatching { client.kv.deletePrefix(PREFIX) }
            client.close()
        }
    }

    @Test
    fun `put get and delete a key`() {
        val key = "${PREFIX}basic"
        assertNull("key should not exist yet", client.kv.get(key))

        client.kv.put(key, "hello")
        val stored = client.kv.get(key)
        assertNotNull(stored)
        assertEquals("hello", stored!!.valueText)
        assertEquals(1L, stored.version)

        client.kv.put(key, "world")
        assertEquals("world", client.kv.get(key)!!.valueText)
        assertEquals(2L, client.kv.get(key)!!.version)

        assertEquals(1L, client.kv.delete(key))
        assertNull(client.kv.get(key))
    }

    @Test
    fun `store and read back binary and unicode values`() {
        val binaryKey = "${PREFIX}binary"
        val bytes = byteArrayOf(0, 1, 0xFF.toByte(), 0x7F)
        client.kv.put(binaryKey, bytes)
        assertTrue(bytes.contentEquals(client.kv.get(binaryKey)!!.value))

        val unicodeKey = "${PREFIX}unicode"
        client.kv.put(unicodeKey, "etcd 🚀 çğüöşı")
        assertEquals("etcd 🚀 çğüöşı", client.kv.get(unicodeKey)!!.valueText)
    }

    @Test
    fun `range over a prefix and count`() {
        client.kv.put("${PREFIX}a/1", "1")
        client.kv.put("${PREFIX}a/2", "2")
        client.kv.put("${PREFIX}b/1", "3")

        val all = client.kv.rangePrefix(PREFIX)
        assertEquals(3, all.kvs.size)
        // Results come back sorted by key, which is what the browser tree relies on.
        assertEquals(listOf("${PREFIX}a/1", "${PREFIX}a/2", "${PREFIX}b/1"), all.kvs.map { it.keyText })
        assertEquals(3L, all.count)

        assertEquals(2L, client.kv.countPrefix("${PREFIX}a/"))

        val keysOnly = client.kv.rangePrefix(PREFIX, keysOnly = true)
        assertTrue("keys_only should omit values", keysOnly.kvs.all { it.value.isEmpty() })
    }

    @Test
    fun `paging reports more and resumes after the last key`() {
        repeat(5) { client.kv.put("${PREFIX}page/$it", "v$it") }

        val first = client.kv.rangePrefix("${PREFIX}page/", limit = 2, keysOnly = true)
        assertEquals(2, first.kvs.size)
        assertTrue("etcd should report more pages", first.more)

        // The browser resumes at the byte after the last key it saw.
        val resumeFrom = com.github.senocak.etcd.client.KeyUtil.encode(
            first.kvs.last().keyText.toByteArray(Charsets.UTF_8) + 0,
        )
        val rangeEnd = com.github.senocak.etcd.client.KeyUtil.prefixRange("${PREFIX}page/").second
        val second = client.kv.range(resumeFrom, rangeEnd, limit = 2, keysOnly = true)
        assertEquals(2, second.kvs.size)
        assertEquals("${PREFIX}page/2", second.kvs.first().keyText)
    }

    @Test
    fun `delete a whole prefix`() {
        repeat(4) { client.kv.put("${PREFIX}bulk/$it", "v") }
        client.kv.put("${PREFIX}keep", "v")

        assertEquals(4L, client.kv.deletePrefix("${PREFIX}bulk/"))
        assertNull(client.kv.get("${PREFIX}bulk/0"))
        assertNotNull("delete must not escape its prefix", client.kv.get("${PREFIX}keep"))
    }

    @Test
    fun `putIfAbsent only creates once`() {
        val key = "${PREFIX}cas-create"
        assertTrue(client.kv.putIfAbsent(key, "first".toByteArray()))
        assertFalse("second create must fail", client.kv.putIfAbsent(key, "second".toByteArray()))
        assertEquals("first", client.kv.get(key)!!.valueText)
    }

    @Test
    fun `putIfUnchanged detects a concurrent write`() {
        val key = "${PREFIX}cas-update"
        client.kv.put(key, "v1")
        val loaded = client.kv.get(key)!!

        assertTrue(client.kv.putIfUnchanged(key, "v2".toByteArray(), loaded.modRevision))
        assertEquals("v2", client.kv.get(key)!!.valueText)

        // The stale revision now belongs to a previous version, so the save must be rejected.
        assertFalse(client.kv.putIfUnchanged(key, "v3".toByteArray(), loaded.modRevision))
        assertEquals("v2", client.kv.get(key)!!.valueText)
    }

    @Test
    fun `a lease owns the keys attached to it`() {
        val lease = client.lease.grant(60)
        assertTrue(lease.id != 0L)
        assertTrue(lease.ttl in 1..60)

        val key = "${PREFIX}leased"
        client.kv.put(key, "v", lease = lease.id)
        assertEquals(lease.id, client.kv.get(key)!!.lease)

        val details = client.lease.timeToLive(lease.id)
        assertNotNull(details)
        assertEquals(60L, details!!.grantedTtl)
        assertTrue("attached key should be listed", details.keys.contains(key))

        assertTrue("keep alive should reset the TTL", client.lease.keepAlive(lease.id) > 0)
        assertTrue(client.lease.list().any { it.id == lease.id })

        // Revoking a lease deletes everything attached to it.
        client.lease.revoke(lease.id)
        assertNull(client.kv.get(key))
        assertFalse(client.lease.list().any { it.id == lease.id })
    }

    @Test
    fun `cluster status and members are readable`() {
        val status = client.cluster.status()
        assertTrue("version should be reported", status.version.isNotBlank())
        assertTrue(status.dbSize > 0)
        assertTrue(status.revision > 0)

        val members = client.cluster.memberList()
        assertTrue("a cluster has at least one member", members.isNotEmpty())
        assertTrue(members.all { it.clientUrls.isNotEmpty() })

        // Alarms parse even when there are none.
        assertNotNull(client.cluster.alarms())
    }

    @Test
    fun `watch streams put and delete events`() {
        val key = "${PREFIX}watched"
        val events = java.util.Collections.synchronizedList(mutableListOf<WatchEvent>())
        val bothSeen = CountDownLatch(2)
        val created = CountDownLatch(1)

        val watch = client.watch(
            prefix = PREFIX,
            watchPrefix = true,
            listener = object : EtcdWatchStream.Listener {
                override fun onCreated(watchId: Long) = created.countDown()

                override fun onEvent(event: WatchEvent) {
                    if (event.kv.keyText == key) {
                        events.add(event)
                        bothSeen.countDown()
                    }
                }

                override fun onClosed(error: EtcdException?) = Unit
            },
        )

        try {
            assertTrue("watch was never created", created.await(10, TimeUnit.SECONDS))
            client.kv.put(key, "v1")
            client.kv.delete(key)
            assertTrue("expected a PUT and a DELETE event", bothSeen.await(15, TimeUnit.SECONDS))
        } finally {
            watch.stop()
        }

        val put = events.first { it.type == WatchEventType.PUT }
        assertEquals("v1", put.kv.valueText)

        val delete = events.first { it.type == WatchEventType.DELETE }
        assertEquals(key, delete.kv.keyText)
        // prev_kv is requested, so a delete still reveals what was removed.
        assertEquals("v1", delete.prevKv?.valueText)
    }

    @Test
    fun `a refused endpoint fails with a clear message`() {
        // Port 1 is reserved and never listening, so this exercises the transport error path.
        EtcdClient(EtcdConnection("unreachable", "http://127.0.0.1:1")) { null }.use {
            val error = runCatching { it.cluster.status() }.exceptionOrNull()
            assertTrue("expected an EtcdException, got $error", error is EtcdException)
            assertTrue(error!!.message!!.contains("127.0.0.1:1"))
        }
    }

    private companion object {
        /** Every key this test touches lives under here and is deleted afterwards. */
        const val PREFIX = "/claude-integration-test/"
    }
}
