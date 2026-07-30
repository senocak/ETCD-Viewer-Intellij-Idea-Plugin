package com.github.senocak.etcd.model

import com.github.senocak.etcd.client.KeyUtil
import com.github.senocak.etcd.json.JsonObject

/**
 * A single key/value pair as etcd reports it.
 *
 * [key] and [value] hold the raw bytes; [keyText] and [valueText] are the UTF-8 reading used
 * throughout the UI. A `keys_only` range leaves [value] empty.
 */
data class EtcdKeyValue(
    val key: ByteArray,
    val value: ByteArray,
    val createRevision: Long,
    val modRevision: Long,
    val version: Long,
    val lease: Long,
) {
    val keyText: String get() = String(key, Charsets.UTF_8)
    val valueText: String get() = String(value, Charsets.UTF_8)

    /** Data classes compare arrays by identity, so key/value need explicit content comparison. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EtcdKeyValue) return false
        return key.contentEquals(other.key) &&
            value.contentEquals(other.value) &&
            createRevision == other.createRevision &&
            modRevision == other.modRevision &&
            version == other.version &&
            lease == other.lease
    }

    override fun hashCode(): Int {
        var result = key.contentHashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + modRevision.hashCode()
        return result
    }

    companion object {
        fun from(json: JsonObject): EtcdKeyValue = EtcdKeyValue(
            key = KeyUtil.decode(json.string("key")),
            value = KeyUtil.decode(json.string("value")),
            createRevision = json.long("create_revision"),
            modRevision = json.long("mod_revision"),
            version = json.long("version"),
            lease = json.long("lease"),
        )
    }
}

/** The outcome of a range query, including the header revision the read was served at. */
data class RangeResult(
    val kvs: List<EtcdKeyValue>,
    val count: Long,
    val more: Boolean,
    val revision: Long,
)

data class EtcdLease(
    val id: Long,
    val ttl: Long,
    val grantedTtl: Long,
    val keys: List<String>,
) {
    /** etcd reports a TTL of -1 once a lease has expired or was never granted. */
    val expired: Boolean get() = ttl < 0
}

data class EtcdMember(
    val id: Long,
    val name: String,
    val peerUrls: List<String>,
    val clientUrls: List<String>,
    val isLearner: Boolean,
) {
    /** Members are conventionally identified by their unsigned 64-bit id in hex. */
    val idHex: String get() = java.lang.Long.toUnsignedString(id, 16)

    companion object {
        fun from(json: JsonObject): EtcdMember = EtcdMember(
            id = json.long("ID"),
            name = json.string("name"),
            peerUrls = json.strings("peerURLs"),
            clientUrls = json.strings("clientURLs"),
            isLearner = json.bool("isLearner"),
        )
    }
}

data class EtcdStatus(
    val version: String,
    val dbSize: Long,
    val dbSizeInUse: Long,
    val leader: Long,
    val raftIndex: Long,
    val raftTerm: Long,
    val revision: Long,
    val memberId: Long,
    val clusterId: Long,
    val errors: List<String>,
)

data class EtcdAlarm(val memberId: Long, val alarm: String)

enum class WatchEventType { PUT, DELETE }

data class WatchEvent(
    val type: WatchEventType,
    val kv: EtcdKeyValue,
    val prevKv: EtcdKeyValue?,
)

data class EtcdUser(val name: String, val roles: List<String>)

enum class PermissionType { READ, WRITE, READWRITE }

/**
 * A role permission over a single key or a range.
 *
 * A permission whose [rangeEnd] is the byte after [key]'s prefix covers that whole prefix, which
 * is how `etcdctl role grant-permission --prefix` expresses itself.
 */
data class EtcdPermission(
    val type: PermissionType,
    val key: ByteArray,
    val rangeEnd: ByteArray,
) {
    val keyText: String get() = String(key, Charsets.UTF_8)

    val rangeEndText: String get() = String(rangeEnd, Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EtcdPermission) return false
        return type == other.type && key.contentEquals(other.key) && rangeEnd.contentEquals(other.rangeEnd)
    }

    override fun hashCode(): Int = 31 * (31 * type.hashCode() + key.contentHashCode()) + rangeEnd.contentHashCode()
}

data class EtcdRole(val name: String, val permissions: List<EtcdPermission>)
