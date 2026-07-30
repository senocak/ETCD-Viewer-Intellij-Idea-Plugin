package com.github.senocak.etcd

import com.github.senocak.etcd.client.EtcdWatchStream
import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.model.EtcdKeyValue
import com.github.senocak.etcd.model.EtcdMember
import com.github.senocak.etcd.model.WatchEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses payloads captured verbatim from an etcd 3.6.7 gRPC-gateway.
 *
 * These fixtures are the contract the client is written against — particularly the proto3-JSON
 * habits of encoding int64 as strings and omitting fields holding their default value.
 */
class EtcdResponseParsingTest {

    @Test
    fun `parses a keys-only range response`() {
        val response = Json.parseObject(
            """
            {"header":{"cluster_id":"14841639068965178418","member_id":"10276657743932975437",
            "revision":"18","raft_term":"2"},
            "kvs":[{"key":"L2FwcC9jb25maWcvY2FjaGU=","create_revision":"15","mod_revision":"15","version":"1"},
            {"key":"L2FwcC9jb25maWcvZGI=","create_revision":"14","mod_revision":"14","version":"1"}],
            "count":"2"}
            """.trimIndent(),
        )

        val kvs = response.objects("kvs").map { EtcdKeyValue.from(it) }
        assertEquals(2, kvs.size)
        assertEquals("/app/config/cache", kvs[0].keyText)
        assertEquals("/app/config/db", kvs[1].keyText)
        assertEquals(15L, kvs[0].modRevision)
        // keys_only omits the value entirely rather than sending an empty string.
        assertEquals(0, kvs[0].value.size)
        // A lease of 0 is the default, so etcd leaves the field out.
        assertEquals(0L, kvs[0].lease)
        assertEquals(2L, response.long("count"))
        assertFalse(response.bool("more"))
        assertEquals(18L, response.obj("header")?.long("revision"))
    }

    @Test
    fun `parses a value carrying range response`() {
        val response = Json.parseObject(
            """
            {"kvs":[{"key":"L2FwcC9jb25maWcvZGI=","create_revision":"14","mod_revision":"14",
            "version":"1","value":"eyJob3N0IjoicGciLCJwb3J0Ijo1NDMyfQ=="}],"count":"1"}
            """.trimIndent(),
        )
        val kv = EtcdKeyValue.from(response.objects("kvs").single())
        assertEquals("/app/config/db", kv.keyText)
        assertEquals("""{"host":"pg","port":5432}""", kv.valueText)
    }

    @Test
    fun `parses a member list response`() {
        val response = Json.parseObject(
            """
            {"header":{"cluster_id":"14841639068965178418","member_id":"10276657743932975437","raft_term":"2"},
            "members":[{"ID":"10276657743932975437","name":"default",
            "peerURLs":["http://localhost:2380"],"clientURLs":["http://127.0.0.1:2379"]}]}
            """.trimIndent(),
        )
        val member = EtcdMember.from(response.objects("members").single())
        assertEquals("default", member.name)
        assertEquals(listOf("http://127.0.0.1:2379"), member.clientUrls)
        assertEquals(listOf("http://localhost:2380"), member.peerUrls)
        // isLearner defaults to false and so is absent from the payload.
        assertFalse(member.isLearner)
        // The id exceeds Long.MAX_VALUE, so read as a signed long it is negative; rendering it
        // unsigned is what makes it match what `etcdctl member list` prints.
        assertTrue(member.id < 0)
        assertEquals("8e9e05c52164694d", member.idHex)
    }

    @Test
    fun `parses a maintenance status response`() {
        val response = Json.parseObject(
            """
            {"header":{"cluster_id":"14841639068965178418","member_id":"10276657743932975437",
            "revision":"18","raft_term":"2"},"version":"3.6.7","dbSize":"36864",
            "leader":"10276657743932975437","raftIndex":"28","raftTerm":"2","dbSizeInUse":"36864"}
            """.trimIndent(),
        )
        assertEquals("3.6.7", response.string("version"))
        assertEquals(36864L, response.long("dbSize"))
        assertEquals(28L, response.long("raftIndex"))
        // No alarms and no errors means both fields are simply absent.
        assertTrue(response.strings("errors").isEmpty())
    }

    @Test
    fun `parses a lease time-to-live response`() {
        val response = Json.parseObject(
            """{"ID":"7587896514996394330","TTL":"59","grantedTTL":"60"}""",
        )
        assertEquals(7587896514996394330L, response.long("ID"))
        assertEquals(59L, response.long("TTL"))
        assertEquals(60L, response.long("grantedTTL"))
        assertTrue(response.strings("keys").isEmpty())
    }

    @Test
    fun `unwraps a streamed keepalive response`() {
        // Streaming RPCs come back wrapped in "result" even for a single reply.
        val response = Json.parseObject(
            """{"result":{"header":{"revision":"18"},"ID":"7587896514996394330","TTL":"60"}}""",
        )
        assertEquals(60L, response.obj("result")?.long("TTL"))
    }

    @Test
    fun `treats a watch event without a type as a PUT`() {
        val result = Json.parseObject(
            """
            {"header":{"revision":"19"},"events":[{"kv":{"key":"L2FwcC9ub2Rlcy9ub2RlLTM=",
            "create_revision":"19","mod_revision":"19","version":"1","value":"Ym9vdGluZw=="}}]}
            """.trimIndent(),
        )
        val event = EtcdWatchStream.parseEvents(result).single()
        // PUT is enum value 0, so etcd omits "type" for it.
        assertEquals(WatchEventType.PUT, event.type)
        assertEquals("/app/nodes/node-3", event.kv.keyText)
        assertEquals("booting", event.kv.valueText)
        assertNull(event.prevKv)
    }

    @Test
    fun `parses a watch delete event with its previous value`() {
        val result = Json.parseObject(
            """
            {"header":{"revision":"20"},"events":[{"type":"DELETE",
            "kv":{"key":"L2FwcC9ub2Rlcy9ub2RlLTM=","mod_revision":"20"},
            "prev_kv":{"key":"L2FwcC9ub2Rlcy9ub2RlLTM=","create_revision":"19","mod_revision":"19",
            "version":"1","value":"Ym9vdGluZw=="}}]}
            """.trimIndent(),
        )
        val event = EtcdWatchStream.parseEvents(result).single()
        assertEquals(WatchEventType.DELETE, event.type)
        assertEquals("/app/nodes/node-3", event.kv.keyText)
        // A delete carries no value, only the revision it happened at.
        assertEquals("", event.kv.valueText)
        assertEquals("booting", event.prevKv?.valueText)
    }

    @Test
    fun `ignores the watch creation frame`() {
        val result = Json.parseObject("""{"header":{"revision":"18"},"created":true}""")
        assertTrue(result.bool("created"))
        assertTrue(EtcdWatchStream.parseEvents(result).isEmpty())
    }

    @Test
    fun `parses a role permission list`() {
        // READ is enum value 0, so a read-only permission omits permType.
        val response = Json.parseObject(
            """{"perm":[{"key":"L2FwcC8=","range_end":"L2FwcDA="},{"permType":"READWRITE","key":"L3N2Yy8="}]}""",
        )
        val permissions = response.objects("perm")
        assertEquals("READ", permissions[0].string("permType", "READ"))
        assertEquals("READWRITE", permissions[1].string("permType", "READ"))
    }
}
