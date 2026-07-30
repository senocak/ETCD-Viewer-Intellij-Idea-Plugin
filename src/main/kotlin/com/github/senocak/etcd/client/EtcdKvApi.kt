package com.github.senocak.etcd.client

import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.json.JsonObject
import com.github.senocak.etcd.model.EtcdKeyValue
import com.github.senocak.etcd.model.RangeResult

enum class SortOrder { NONE, ASCEND, DESCEND }

enum class SortTarget { KEY, VERSION, CREATE, MOD, VALUE }

/** The `/v3/kv/…` endpoints: reads, writes, deletes, transactions and compaction. */
class EtcdKvApi(private val transport: EtcdHttpTransport) {

    /** Reads a single key, or null when it does not exist. */
    fun get(key: String): EtcdKeyValue? {
        val response = transport.post("/v3/kv/range", Json.obj("key" to KeyUtil.encode(key)))
        return response.objects("kvs").firstOrNull()?.let { EtcdKeyValue.from(it) }
    }

    /**
     * Reads every key under [prefix].
     *
     * Pass [keysOnly] when populating the browser tree so etcd does not ship values that are
     * about to be discarded. [limit] bounds the page; the result's `more` flag says whether
     * etcd had further keys to give.
     */
    fun rangePrefix(
        prefix: String,
        limit: Int = 0,
        keysOnly: Boolean = false,
        revision: Long = 0,
        sortOrder: SortOrder = SortOrder.ASCEND,
        sortTarget: SortTarget = SortTarget.KEY,
    ): RangeResult {
        val (key, rangeEnd) = KeyUtil.prefixRange(prefix)
        return range(key, rangeEnd, limit, keysOnly, revision, sortOrder, sortTarget)
    }

    /**
     * Reads the half-open range `[fromKey, toKey)`, where both bounds are already base64.
     *
     * Used directly for paging: the next page starts at the byte after the last key returned.
     */
    fun range(
        keyBase64: String,
        rangeEndBase64: String,
        limit: Int = 0,
        keysOnly: Boolean = false,
        revision: Long = 0,
        sortOrder: SortOrder = SortOrder.ASCEND,
        sortTarget: SortTarget = SortTarget.KEY,
    ): RangeResult {
        val response = transport.post(
            "/v3/kv/range",
            Json.obj(
                "key" to keyBase64,
                "range_end" to rangeEndBase64,
                "limit" to limit.takeIf { it > 0 }?.toLong(),
                "keys_only" to keysOnly.takeIf { it },
                "revision" to revision.takeIf { it > 0 },
                "sort_order" to sortOrder.name.takeIf { sortOrder != SortOrder.NONE },
                "sort_target" to sortTarget.name.takeIf { sortOrder != SortOrder.NONE },
            ),
        )
        return RangeResult(
            kvs = response.objects("kvs").map { EtcdKeyValue.from(it) },
            count = response.long("count"),
            more = response.bool("more"),
            revision = response.obj("header")?.long("revision") ?: 0L,
        )
    }

    /** Counts the keys under [prefix] without transferring them. */
    fun countPrefix(prefix: String): Long {
        val (key, rangeEnd) = KeyUtil.prefixRange(prefix)
        val response = transport.post(
            "/v3/kv/range",
            Json.obj("key" to key, "range_end" to rangeEnd, "count_only" to true),
        )
        return response.long("count")
    }

    /**
     * Writes [value] to [key].
     *
     * A non-zero [lease] attaches the key to that lease, so it disappears when the lease expires.
     * Returns the previous value when [prevKv] is set.
     */
    fun put(key: String, value: ByteArray, lease: Long = 0, prevKv: Boolean = false): EtcdKeyValue? {
        val response = transport.post(
            "/v3/kv/put",
            Json.obj(
                "key" to KeyUtil.encode(key),
                "value" to KeyUtil.encode(value),
                "lease" to lease.takeIf { it != 0L },
                "prev_kv" to prevKv.takeIf { it },
            ),
        )
        return response.obj("prev_kv")?.let { EtcdKeyValue.from(it) }
    }

    fun put(key: String, value: String, lease: Long = 0, prevKv: Boolean = false): EtcdKeyValue? =
        put(key, value.toByteArray(Charsets.UTF_8), lease, prevKv)

    /** Deletes one key. Returns the number of keys removed, so 0 or 1. */
    fun delete(key: String): Long {
        val response = transport.post("/v3/kv/deleterange", Json.obj("key" to KeyUtil.encode(key)))
        return response.long("deleted")
    }

    /** Deletes every key under [prefix]. Returns how many were removed. */
    fun deletePrefix(prefix: String): Long {
        val (key, rangeEnd) = KeyUtil.prefixRange(prefix)
        val response = transport.post(
            "/v3/kv/deleterange",
            Json.obj("key" to key, "range_end" to rangeEnd),
        )
        return response.long("deleted")
    }

    /**
     * Writes [key] only if it does not already exist, via a compare-and-set transaction.
     *
     * Guards the "create key" action so two people creating the same key cannot silently
     * overwrite each other. Returns true when the key was created.
     */
    fun putIfAbsent(key: String, value: ByteArray, lease: Long = 0): Boolean {
        val encodedKey = KeyUtil.encode(key)
        val response = transport.post(
            "/v3/kv/txn",
            Json.obj(
                // version == 0 is etcd's idiom for "this key does not exist".
                "compare" to listOf(
                    Json.obj("target" to "VERSION", "result" to "EQUAL", "key" to encodedKey, "version" to 0L),
                ),
                "success" to listOf(
                    Json.obj(
                        "requestPut" to Json.obj(
                            "key" to encodedKey,
                            "value" to KeyUtil.encode(value),
                            "lease" to lease.takeIf { it != 0L },
                        ),
                    ),
                ),
            ),
        )
        return response.bool("succeeded")
    }

    /**
     * Writes [key] only if its current revision is still [expectedModRevision].
     *
     * The value editor uses this so saving cannot clobber a change made by someone else since
     * the value was loaded. Returns false when the revision moved on.
     */
    fun putIfUnchanged(key: String, value: ByteArray, expectedModRevision: Long, lease: Long = 0): Boolean {
        val encodedKey = KeyUtil.encode(key)
        val response = transport.post(
            "/v3/kv/txn",
            Json.obj(
                "compare" to listOf(
                    Json.obj(
                        "target" to "MOD",
                        "result" to "EQUAL",
                        "key" to encodedKey,
                        "modRevision" to expectedModRevision,
                    ),
                ),
                "success" to listOf(
                    Json.obj(
                        "requestPut" to Json.obj(
                            "key" to encodedKey,
                            "value" to KeyUtil.encode(value),
                            "lease" to lease.takeIf { it != 0L },
                        ),
                    ),
                ),
            ),
        )
        return response.bool("succeeded")
    }

    /** Runs a raw transaction, for callers that need the full compare/success/failure shape. */
    fun txn(request: JsonObject): JsonObject = transport.post("/v3/kv/txn", request)

    /**
     * Discards history at or before [revision].
     *
     * Irreversible: watches starting from a compacted revision will fail afterwards.
     */
    fun compact(revision: Long, physical: Boolean = false): JsonObject =
        transport.post(
            "/v3/kv/compaction",
            Json.obj("revision" to revision, "physical" to physical.takeIf { it }),
        )
}
