package com.github.senocak.etcd.client

import com.github.senocak.etcd.json.Json
import com.github.senocak.etcd.model.EtcdPermission
import com.github.senocak.etcd.model.EtcdRole
import com.github.senocak.etcd.model.EtcdUser
import com.github.senocak.etcd.model.PermissionType

/**
 * The `/v3/auth/…` endpoints.
 *
 * etcd's auth API is inconsistent about what it calls its parameters — some requests take
 * `name`, others `user` or `role` for the same thing. The field names below follow
 * `rpc.proto` exactly and should not be "tidied up".
 */
class EtcdAuthApi(private val transport: EtcdHttpTransport) {

    fun isEnabled(): Boolean =
        // etcd has no "is auth on?" call; a status probe answers PermissionDenied when it is.
        try {
            transport.post("/v3/auth/user/list")
            true
        } catch (e: EtcdException) {
            if (e.isAuthTokenRejected || e.isPermissionDenied) true else throw e
        }

    fun enable() {
        transport.post("/v3/auth/enable")
    }

    fun disable() {
        transport.post("/v3/auth/disable")
    }

    fun userList(): List<String> = transport.post("/v3/auth/user/list").strings("users")

    /** Lists users with the roles granted to each. */
    fun users(): List<EtcdUser> = userList().map { name ->
        EtcdUser(name, runCatching { userRoles(name) }.getOrDefault(emptyList()))
    }

    fun userRoles(name: String): List<String> =
        transport.post("/v3/auth/user/get", Json.obj("name" to name)).strings("roles")

    fun userAdd(name: String, password: String) {
        transport.post("/v3/auth/user/add", Json.obj("name" to name, "password" to password))
    }

    fun userDelete(name: String) {
        transport.post("/v3/auth/user/delete", Json.obj("name" to name))
    }

    fun userChangePassword(name: String, password: String) {
        transport.post("/v3/auth/user/changepw", Json.obj("name" to name, "password" to password))
    }

    fun userGrantRole(user: String, role: String) {
        transport.post("/v3/auth/user/grant", Json.obj("user" to user, "role" to role))
    }

    fun userRevokeRole(user: String, role: String) {
        transport.post("/v3/auth/user/revoke", Json.obj("name" to user, "role" to role))
    }

    fun roleList(): List<String> = transport.post("/v3/auth/role/list").strings("roles")

    /** Lists roles with their permissions resolved. */
    fun roles(): List<EtcdRole> = roleList().map { name ->
        EtcdRole(name, runCatching { rolePermissions(name) }.getOrDefault(emptyList()))
    }

    fun rolePermissions(role: String): List<EtcdPermission> =
        transport.post("/v3/auth/role/get", Json.obj("role" to role))
            .objects("perm")
            .map {
                EtcdPermission(
                    // READ is enum value 0, which proto3 JSON omits, so absent means READ.
                    type = runCatching { PermissionType.valueOf(it.string("permType", "READ")) }
                        .getOrDefault(PermissionType.READ),
                    key = KeyUtil.decode(it.string("key")),
                    rangeEnd = KeyUtil.decode(it.string("range_end")),
                )
            }

    fun roleAdd(name: String) {
        transport.post("/v3/auth/role/add", Json.obj("name" to name))
    }

    fun roleDelete(role: String) {
        transport.post("/v3/auth/role/delete", Json.obj("role" to role))
    }

    /**
     * Grants [type] over a single key, or over a whole prefix when [asPrefix] is set.
     *
     * An empty [key] with [asPrefix] grants access to the entire key space, matching
     * `etcdctl role grant-permission --prefix '' `.
     */
    fun roleGrantPermission(role: String, key: String, type: PermissionType, asPrefix: Boolean) {
        val encodedKey: String
        val rangeEnd: String?
        if (asPrefix) {
            val range = KeyUtil.prefixRange(key)
            encodedKey = range.first
            rangeEnd = range.second
        } else {
            encodedKey = KeyUtil.encode(key)
            rangeEnd = null
        }
        transport.post(
            "/v3/auth/role/grant",
            Json.obj(
                "name" to role,
                "perm" to Json.obj("permType" to type.name, "key" to encodedKey, "range_end" to rangeEnd),
            ),
        )
    }

    /** Revokes a permission. [rangeEndBase64] must match what [rolePermissions] reported. */
    fun roleRevokePermission(role: String, keyBase64: String, rangeEndBase64: String) {
        transport.post(
            "/v3/auth/role/revoke",
            Json.obj(
                "role" to role,
                "key" to keyBase64,
                "range_end" to rangeEndBase64.takeIf { it.isNotEmpty() },
            ),
        )
    }
}
