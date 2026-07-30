package com.github.senocak.etcd.client

import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.model.EtcdLease

/** The `/v3/lease/…` endpoints. */
class EtcdLeaseApi(private val transport: EtcdHttpTransport) {

    /**
     * Grants a lease living [ttlSeconds] seconds. Pass [id] to request a specific lease id,
     * or leave it at 0 to let etcd allocate one. Returns the granted lease.
     */
    fun grant(ttlSeconds: Long, id: Long = 0): EtcdLease {
        val response = transport.post(
            "/v3/lease/grant",
            Json.obj("TTL" to ttlSeconds, "ID" to id.takeIf { it != 0L }),
        )
        val error = response.string("error")
        if (error.isNotEmpty()) throw EtcdException(error)
        return EtcdLease(
            id = response.long("ID"),
            ttl = response.long("TTL"),
            grantedTtl = response.long("TTL"),
            keys = emptyList(),
        )
    }

    /** Revokes a lease, deleting every key attached to it. */
    fun revoke(id: Long) {
        transport.post("/v3/lease/revoke", Json.obj("ID" to id))
    }

    /** Resets a lease's TTL to its granted value. Returns the remaining TTL in seconds. */
    fun keepAlive(id: Long): Long {
        // /v3/lease/keepalive is a streaming RPC, so the gateway wraps its reply in "result".
        val response = transport.post("/v3/lease/keepalive", Json.obj("ID" to id))
        val result = response.obj("result") ?: response
        return result.long("TTL", -1)
    }

    /**
     * Looks up a lease's remaining TTL, and optionally the keys attached to it.
     *
     * Returns null if etcd does not know the lease — it expired or was already revoked.
     */
    fun timeToLive(id: Long, withKeys: Boolean = true): EtcdLease? {
        val response = transport.post(
            "/v3/lease/timetolive",
            Json.obj("ID" to id, "keys" to withKeys.takeIf { it }),
        )
        val ttl = response.long("TTL", -1)
        if (ttl == -1L && !response.has("grantedTTL")) return null
        return EtcdLease(
            id = response.long("ID", id),
            ttl = ttl,
            grantedTtl = response.long("grantedTTL"),
            keys = response.strings("keys").map { KeyUtil.decodeToString(it) },
        )
    }

    /**
     * Lists every live lease, each resolved to its TTL and attached keys.
     *
     * `/v3/lease/leases` only returns ids, so each one needs a follow-up `timetolive` call.
     * Leases that expire between the two calls are simply dropped from the result.
     */
    fun list(): List<EtcdLease> {
        val response = transport.post("/v3/lease/leases")
        return response.objects("leases")
            .map { it.long("ID") }
            .mapNotNull { id -> runCatching { timeToLive(id) }.getOrNull() }
    }
}
