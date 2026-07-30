package com.github.senocak.etcd

import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.json.JsonArray
import com.github.senocak.etcd.json.JsonObject
import com.github.senocak.etcd.json.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `parses a nested object`() {
        val parsed = Json.parseObject("""{"a":1,"b":{"c":"x"},"d":[1,2,3],"e":true,"f":null}""")
        assertEquals(1, parsed.int("a"))
        assertEquals("x", parsed.obj("b")?.string("c"))
        assertEquals(3, parsed.array("d")?.size)
        assertTrue(parsed.bool("e"))
        // A null field is treated as absent, which matches how proto3 omits defaults.
        assertFalse(parsed.has("f"))
    }

    @Test
    fun `reads int64 encoded as a string`() {
        // etcd sends every int64 as a JSON string; large lease ids must survive intact.
        val parsed = Json.parseObject("""{"revision":"9007199254740993","lease":"7587896514996394330"}""")
        assertEquals(9007199254740993L, parsed.long("revision"))
        assertEquals(7587896514996394330L, parsed.long("lease"))
    }

    @Test
    fun `reads int64 encoded as a number`() {
        assertEquals(42L, Json.parseObject("""{"n":42}""").long("n"))
    }

    @Test
    fun `reads unsigned int64 above Long MAX_VALUE`() {
        // Member, cluster and lease ids are unsigned; a plain toLong would fail and silently
        // yield 0. They must round-trip through the same 64 bits instead.
        val parsed = Json.parseObject(
            """{"member_id":"10276657743932975437","cluster_id":"14841639068965178418"}""",
        )
        val memberId = parsed.long("member_id")
        assertTrue(memberId < 0)
        assertEquals("10276657743932975437", java.lang.Long.toUnsignedString(memberId))
        assertEquals("8e9e05c52164694d", java.lang.Long.toUnsignedString(memberId, 16))
        assertEquals(
            "14841639068965178418",
            java.lang.Long.toUnsignedString(parsed.long("cluster_id")),
        )
    }

    @Test
    fun `absent fields fall back to defaults`() {
        val parsed = Json.parseObject("{}")
        assertEquals(0L, parsed.long("missing"))
        assertEquals(7L, parsed.long("missing", 7))
        assertEquals("", parsed.string("missing"))
        assertFalse(parsed.bool("missing"))
        assertTrue(parsed.objects("missing").isEmpty())
        assertTrue(parsed.strings("missing").isEmpty())
    }

    @Test
    fun `round trips escapes`() {
        val text = "quote \" backslash \\ newline \n tab \t control "
        val written = Json.obj("v" to text).write()
        assertEquals(text, Json.parseObject(written).string("v"))
    }

    @Test
    fun `round trips characters outside the basic plane`() {
        // A surrogate pair must survive both writing and the \uXXXX parse path.
        val emoji = "etcd 🚀 ok"
        assertEquals(emoji, Json.parseObject(Json.obj("v" to emoji).write()).string("v"))
        assertEquals(emoji, Json.parseObject("""{"v":"etcd 🚀 ok"}""").string("v"))
    }

    @Test
    fun `writes longs as strings and ints as numbers`() {
        // etcd accepts both, but mirroring its own encoding avoids precision surprises.
        assertEquals("""{"a":"5","b":3}""", Json.obj("a" to 5L, "b" to 3).write())
    }

    @Test
    fun `null values are omitted from built objects`() {
        assertEquals("""{"a":"1"}""", Json.obj("a" to 1L, "b" to null).write())
    }

    @Test
    fun `builds nested arrays and objects`() {
        val built = Json.obj("list" to listOf("a", "b"), "obj" to Json.obj("k" to true))
        assertEquals("""{"list":["a","b"],"obj":{"k":true}}""", built.write())
    }

    @Test
    fun `parses empty containers`() {
        assertEquals(0, (Json.parse("[]") as JsonArray).size)
        assertTrue((Json.parse("{}") as JsonObject).fields.isEmpty())
    }

    @Test(expected = JsonParseException::class)
    fun `rejects trailing content`() {
        Json.parse("""{"a":1} garbage""")
    }

    @Test(expected = JsonParseException::class)
    fun `rejects an unterminated object`() {
        Json.parse("""{"a":1""")
    }

    @Test(expected = JsonParseException::class)
    fun `rejects an invalid literal`() {
        Json.parse("tru")
    }
}
