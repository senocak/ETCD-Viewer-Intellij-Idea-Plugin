package com.github.senocak.etcd.client

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Encoding helpers for etcd's key space.
 *
 * etcd keys and values are opaque byte strings, and the gRPC-gateway carries them as base64.
 * Everything that converts between what the user sees and what goes on the wire lives here.
 */
object KeyUtil {

    /** etcd's sentinel for "unbounded": a single zero byte, used as both key and range end. */
    private val ZERO_BYTE = byteArrayOf(0)

    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun encode(text: String): String = encode(text.toByteArray(StandardCharsets.UTF_8))

    fun decode(base64: String): ByteArray =
        if (base64.isEmpty()) ByteArray(0) else decoder.decode(base64)

    /** Decodes to text; bytes that are not valid UTF-8 come back as replacement characters. */
    fun decodeToString(base64: String): String = String(decode(base64), StandardCharsets.UTF_8)

    /**
     * The exclusive upper bound covering every key starting with [prefix].
     *
     * Increments the last byte below `0xFF` and drops everything after it, mirroring etcd's own
     * `getPrefix`. An empty prefix — or one consisting entirely of `0xFF` — has no such bound,
     * so it falls back to the zero-byte sentinel meaning "to the end of the key space".
     */
    fun prefixRangeEnd(prefix: ByteArray): ByteArray {
        for (i in prefix.indices.reversed()) {
            val b = prefix[i].toInt() and 0xFF
            if (b < 0xFF) {
                return prefix.copyOf(i + 1).also { it[i] = (b + 1).toByte() }
            }
        }
        return ZERO_BYTE
    }

    /**
     * The `(key, range_end)` pair for a prefix scan, already base64 encoded.
     *
     * An empty prefix scans the whole key space, which etcd expresses as key `\0` with range end
     * `\0` rather than as an empty range.
     */
    fun prefixRange(prefix: String): Pair<String, String> {
        if (prefix.isEmpty()) return encode(ZERO_BYTE) to encode(ZERO_BYTE)
        val bytes = prefix.toByteArray(StandardCharsets.UTF_8)
        return encode(bytes) to encode(prefixRangeEnd(bytes))
    }

    /**
     * The immediate child of [key] below [prefix].
     *
     * Used to fold etcd's flat key space into a tree: under prefix `/app/` the key
     * `/app/nodes/node-1` yields the folder `nodes` with child prefix `/app/nodes/`, while
     * `/app/name` yields the leaf `name`. Returns null when [key] does not sit under [prefix].
     *
     * Separators directly after [prefix] are skipped, so the conventional leading `/` on etcd
     * keys does not produce an unnamed folder above the first real segment.
     */
    fun childSegment(prefix: String, key: String, separator: String): ChildSegment? {
        if (!key.startsWith(prefix) || key.length <= prefix.length) return null
        var offset = prefix.length
        if (separator.isNotEmpty()) {
            while (offset < key.length && key.startsWith(separator, offset)) offset += separator.length
        }
        if (offset >= key.length) return null

        val rest = key.substring(offset)
        val idx = if (separator.isEmpty()) -1 else rest.indexOf(separator)
        return if (idx < 0) {
            ChildSegment(name = rest, isFolder = false, childPrefix = key)
        } else {
            ChildSegment(
                name = rest.substring(0, idx),
                isFolder = true,
                // Include the trailing separator so the result is a usable range prefix.
                childPrefix = key.substring(0, offset + idx + separator.length),
            )
        }
    }

    /**
     * One level of the key tree.
     *
     * [childPrefix] is what the next range query scans; for a leaf it is simply the key.
     */
    data class ChildSegment(val name: String, val isFolder: Boolean, val childPrefix: String)

    /** Renders bytes as space-separated hex, for values that are not printable text. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Whether [bytes] round-trips through UTF-8 unchanged, i.e. is safe to show and edit as text.
     * Values that fail this are presented in the Base64 or Hex view instead.
     */
    fun isPrintableText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) return false
        return text.none { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
    }
}
