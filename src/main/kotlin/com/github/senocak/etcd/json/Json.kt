package com.github.senocak.etcd.json

/**
 * A minimal, dependency-free JSON codec.
 *
 * The plugin talks to etcd's v3 gRPC-gateway, whose payloads are plain proto3-JSON: objects,
 * arrays, strings, numbers, booleans and null. Rather than pull in a third-party JSON library
 * (and risk clashing with whatever the IDE bundles), the whole surface is handled here.
 *
 * Two proto3-JSON quirks drive the design:
 *  - `int64` fields are serialised as JSON *strings* (`"revision":"42"`), so [JsonObject.long]
 *    accepts both strings and numbers.
 *  - Fields holding their default value are omitted entirely, so every accessor takes a default.
 */
sealed class JsonValue {
    fun write(): String = StringBuilder().also { writeTo(it) }.toString()

    internal abstract fun writeTo(out: StringBuilder)
}

object JsonNull : JsonValue() {
    override fun writeTo(out: StringBuilder) {
        out.append("null")
    }
}

data class JsonBool(val value: Boolean) : JsonValue() {
    override fun writeTo(out: StringBuilder) {
        out.append(if (value) "true" else "false")
    }
}

/** Numbers keep their original text so large int64 values survive a round trip intact. */
data class JsonNumber(val text: String) : JsonValue() {
    override fun writeTo(out: StringBuilder) {
        out.append(text)
    }
}

data class JsonString(val value: String) : JsonValue() {
    override fun writeTo(out: StringBuilder) {
        Json.escape(value, out)
    }
}

data class JsonArray(val items: List<JsonValue>) : JsonValue() {
    val size: Int get() = items.size

    operator fun get(index: Int): JsonValue = items[index]

    override fun writeTo(out: StringBuilder) {
        out.append('[')
        items.forEachIndexed { i, item ->
            if (i > 0) out.append(',')
            item.writeTo(out)
        }
        out.append(']')
    }
}

data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue() {
    operator fun get(name: String): JsonValue? = fields[name]?.takeIf { it !is JsonNull }

    fun has(name: String): Boolean = get(name) != null

    fun string(name: String, default: String = ""): String = when (val v = get(name)) {
        is JsonString -> v.value
        is JsonNumber -> v.text
        is JsonBool -> v.value.toString()
        else -> default
    }

    /**
     * Reads an int64 that etcd may have encoded as either a JSON string or a JSON number.
     *
     * Member ids, cluster ids and lease ids are *unsigned* 64-bit and routinely exceed
     * [Long.MAX_VALUE], where a plain `toLong` would fail. Those are reinterpreted through
     * [ULong] so the same 64 bits come back — negative when read as signed, which is why they
     * must be rendered with `java.lang.Long.toUnsignedString`.
     */
    fun long(name: String, default: Long = 0L): Long = when (val v = get(name)) {
        is JsonString -> v.value.toLongOrNull() ?: v.value.toULongOrNull()?.toLong() ?: default
        is JsonNumber -> v.text.toLongOrNull()
            ?: v.text.toULongOrNull()?.toLong()
            ?: v.text.toDoubleOrNull()?.toLong()
            ?: default

        else -> default
    }

    fun int(name: String, default: Int = 0): Int = long(name, default.toLong()).toInt()

    fun bool(name: String, default: Boolean = false): Boolean = when (val v = get(name)) {
        is JsonBool -> v.value
        is JsonString -> v.value.equals("true", ignoreCase = true)
        else -> default
    }

    fun obj(name: String): JsonObject? = get(name) as? JsonObject

    fun array(name: String): JsonArray? = get(name) as? JsonArray

    /** Array accessor that treats an absent field as empty — proto3 omits empty repeated fields. */
    fun objects(name: String): List<JsonObject> =
        array(name)?.items?.filterIsInstance<JsonObject>() ?: emptyList()

    fun strings(name: String): List<String> =
        array(name)?.items?.mapNotNull { (it as? JsonString)?.value } ?: emptyList()

    override fun writeTo(out: StringBuilder) {
        out.append('{')
        var first = true
        for ((key, value) in fields) {
            if (!first) out.append(',')
            first = false
            Json.escape(key, out)
            out.append(':')
            value.writeTo(out)
        }
        out.append('}')
    }
}

class JsonParseException(message: String) : RuntimeException(message)

object Json {

    fun parse(text: String): JsonValue = Parser(text).run {
        val value = parseValue()
        skipWhitespace()
        if (!atEnd()) fail("trailing content")
        value
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonParseException("expected a JSON object")

    /**
     * Builds a request body. `null` values are dropped, which lets callers pass optional
     * request fields unconditionally. Kotlin [Long]s become JSON strings because that is what
     * etcd emits for int64 and what it always accepts.
     */
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject {
        val fields = LinkedHashMap<String, JsonValue>(pairs.size)
        for ((name, value) in pairs) {
            if (value == null) continue
            fields[name] = of(value)
        }
        return JsonObject(fields)
    }

    fun of(value: Any?): JsonValue = when (value) {
        null -> JsonNull
        is JsonValue -> value
        is String -> JsonString(value)
        is Boolean -> JsonBool(value)
        is Long -> JsonString(value.toString())
        is Int -> JsonNumber(value.toString())
        is Double, is Float -> JsonNumber(value.toString())
        is Iterable<*> -> JsonArray(value.map { of(it) })
        is Array<*> -> JsonArray(value.map { of(it) })
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to of(v) })
        else -> JsonString(value.toString())
    }

    internal fun escape(text: String, out: StringBuilder) {
        out.append('"')
        for (ch in text) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '' -> out.append("\\f")
                // Only control characters need escaping; everything else — including the halves
                // of a surrogate pair — is emitted as-is and encoded correctly by UTF-8.
                ch < ' ' -> out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun fail(message: String): Nothing =
            throw JsonParseException("$message at offset $pos")

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (ch == '-' || ch in '0'..'9') parseNumber() else fail("unexpected '$ch'")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
            return value
        }

        private fun parseObject(): JsonObject {
            pos++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') fail("expected a field name")
                val name = parseString()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') fail("expected ':'")
                pos++
                fields[name] = parseValue()
                skipWhitespace()
                if (atEnd()) fail("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonObject(fields) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonArray {
            pos++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return JsonArray(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                if (atEnd()) fail("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonArray(items) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val ch = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("invalid \\u escape")
                                pos += 4
                                // Appending each half separately reassembles surrogate pairs.
                                sb.append(code.toChar())
                            }
                            else -> fail("invalid escape '\\$esc'")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun parseNumber(): JsonNumber {
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            while (!atEnd() && (text[pos] in '0'..'9')) pos++
            if (!atEnd() && text[pos] == '.') {
                pos++
                while (!atEnd() && (text[pos] in '0'..'9')) pos++
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                while (!atEnd() && (text[pos] in '0'..'9')) pos++
            }
            if (pos == start) fail("invalid number")
            return JsonNumber(text.substring(start, pos))
        }
    }
}
