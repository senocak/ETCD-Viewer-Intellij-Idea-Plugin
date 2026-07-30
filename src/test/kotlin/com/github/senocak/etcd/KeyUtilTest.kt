package com.github.senocak.etcd

import com.github.senocak.etcd.client.KeyUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyUtilTest {

    @Test
    fun `prefix range end increments the last byte`() {
        assertArrayEquals("foq".toByteArray(), KeyUtil.prefixRangeEnd("fop".toByteArray()))
        // etcd's own documented example: the range end for "foo" is "fop".
        assertArrayEquals("fop".toByteArray(), KeyUtil.prefixRangeEnd("foo".toByteArray()))
    }

    @Test
    fun `prefix range end drops trailing 0xFF bytes`() {
        val prefix = byteArrayOf(0x61, 0xFF.toByte(), 0xFF.toByte())
        // Only the last byte below 0xFF is incremented, and the 0xFF tail is discarded.
        assertArrayEquals(byteArrayOf(0x62), KeyUtil.prefixRangeEnd(prefix))
    }

    @Test
    fun `an all-0xFF prefix has no upper bound`() {
        val allOnes = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertArrayEquals(byteArrayOf(0), KeyUtil.prefixRangeEnd(allOnes))
        assertArrayEquals(byteArrayOf(0), KeyUtil.prefixRangeEnd(ByteArray(0)))
    }

    @Test
    fun `an empty prefix scans the whole key space`() {
        val (key, rangeEnd) = KeyUtil.prefixRange("")
        // etcd expresses "everything" as key \0 with range end \0, not as an empty range.
        assertEquals("AA==", key)
        assertEquals("AA==", rangeEnd)
    }

    @Test
    fun `prefix range is base64 encoded`() {
        val (key, rangeEnd) = KeyUtil.prefixRange("/app")
        assertEquals("L2FwcA==", key)
        assertEquals("L2FwcQ==", rangeEnd)
    }

    @Test
    fun `base64 round trips arbitrary bytes`() {
        val bytes = byteArrayOf(0, 1, 2, 0xFF.toByte(), 0x7F, 0x80.toByte())
        assertArrayEquals(bytes, KeyUtil.decode(KeyUtil.encode(bytes)))
        assertArrayEquals(ByteArray(0), KeyUtil.decode(""))
    }

    @Test
    fun `child segment distinguishes folders from keys`() {
        assertEquals("nodes" to true, KeyUtil.childSegment("/app/", "/app/nodes/node-1", "/"))
        assertEquals("name" to false, KeyUtil.childSegment("/app/", "/app/name", "/"))
    }

    @Test
    fun `child segment rejects keys outside the prefix`() {
        assertNull(KeyUtil.childSegment("/app/", "/other/key", "/"))
        // A key equal to the prefix has no child segment.
        assertNull(KeyUtil.childSegment("/app/", "/app/", "/"))
    }

    @Test
    fun `printable text detection rejects non-UTF8 and control bytes`() {
        assertTrue(KeyUtil.isPrintableText("hello".toByteArray()))
        assertTrue(KeyUtil.isPrintableText("line\nbreak".toByteArray()))
        assertTrue(KeyUtil.isPrintableText(ByteArray(0)))
        assertFalse(KeyUtil.isPrintableText(byteArrayOf(0xC3.toByte(), 0x28)))
        assertFalse(KeyUtil.isPrintableText(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun `hex rendering pads each byte`() {
        assertEquals("00 0f ff", KeyUtil.toHex(byteArrayOf(0, 0x0F, 0xFF.toByte())))
    }
}
