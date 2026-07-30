package com.github.senocak.etcd.client

import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.model.EtcdAlarm
import com.github.senocak.etcd.model.EtcdMember
import com.github.senocak.etcd.model.EtcdStatus

/** The `/v3/cluster/…` and `/v3/maintenance/…` endpoints. */
class EtcdClusterApi(private val transport: EtcdHttpTransport) {

    fun memberList(): List<EtcdMember> =
        transport.post("/v3/cluster/member/list").objects("members").map { EtcdMember.from(it) }

    /** Adds a member. A learner replicates the log but does not vote until promoted. */
    fun memberAdd(peerUrls: List<String>, isLearner: Boolean = false): List<EtcdMember> {
        val response = transport.post(
            "/v3/cluster/member/add",
            Json.obj("peerURLs" to peerUrls, "isLearner" to isLearner.takeIf { it }),
        )
        return response.objects("members").map { EtcdMember.from(it) }
    }

    fun memberRemove(id: Long): List<EtcdMember> =
        transport.post("/v3/cluster/member/remove", Json.obj("ID" to id))
            .objects("members").map { EtcdMember.from(it) }

    fun memberUpdate(id: Long, peerUrls: List<String>): List<EtcdMember> =
        transport.post("/v3/cluster/member/update", Json.obj("ID" to id, "peerURLs" to peerUrls))
            .objects("members").map { EtcdMember.from(it) }

    /** Promotes a learner to a voting member. */
    fun memberPromote(id: Long): List<EtcdMember> =
        transport.post("/v3/cluster/member/promote", Json.obj("ID" to id))
            .objects("members").map { EtcdMember.from(it) }

    /** Reports version, database size and raft state for the endpoint being talked to. */
    fun status(): EtcdStatus {
        val response = transport.post("/v3/maintenance/status")
        val header = response.obj("header")
        return EtcdStatus(
            version = response.string("version"),
            dbSize = response.long("dbSize"),
            dbSizeInUse = response.long("dbSizeInUse"),
            leader = response.long("leader"),
            raftIndex = response.long("raftIndex"),
            raftTerm = response.long("raftTerm"),
            revision = header?.long("revision") ?: 0L,
            memberId = header?.long("member_id") ?: 0L,
            clusterId = header?.long("cluster_id") ?: 0L,
            errors = response.strings("errors"),
        )
    }

    /** Releases space left behind by compaction. Blocks the member while it runs. */
    fun defragment() {
        transport.post("/v3/maintenance/defragment")
    }

    fun alarms(): List<EtcdAlarm> =
        transport.post("/v3/maintenance/alarm", Json.obj("action" to "GET", "alarm" to "NONE", "memberID" to 0L))
            .objects("alarms")
            .map { EtcdAlarm(memberId = it.long("memberID"), alarm = it.string("alarm", "NONE")) }

    /** Clears an alarm — typically NOSPACE, after a defragment has freed room. */
    fun disarmAlarm(memberId: Long, alarm: String) {
        transport.post(
            "/v3/maintenance/alarm",
            Json.obj("action" to "DEACTIVATE", "alarm" to alarm, "memberID" to memberId),
        )
    }

    /** Hash of the key-value store, for comparing members against each other. */
    fun hashKv(revision: Long = 0): Long =
        transport.post("/v3/maintenance/hashkv", Json.obj("revision" to revision.takeIf { it > 0 }))
            .long("hash")
}
