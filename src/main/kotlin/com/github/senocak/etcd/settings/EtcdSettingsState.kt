package com.github.senocak.etcd.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

/**
 * The saved list of etcd connections, shared by every open project.
 *
 * Passwords are not part of the state — see [EtcdCredentials].
 */
@Service(Service.Level.APP)
@State(name = "EtcdSettings", storages = [Storage("etcd-connections.xml")])
class EtcdSettingsState : PersistentStateComponent<EtcdSettingsState> {

    @XCollection(propertyElementName = "connections")
    var connections: MutableList<EtcdConnection> = mutableListOf()

    /** Id of the connection to preselect in the tool window. */
    var lastUsedConnectionId: String = ""

    override fun getState(): EtcdSettingsState = this

    override fun loadState(state: EtcdSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    /**
     * Replaces the whole list, forgetting passwords for connections that were dropped.
     *
     * The settings page edits a working copy and commits it in one go, so this is the only
     * mutator the list needs.
     */
    fun replaceAll(updated: List<EtcdConnection>) {
        val survivingIds = updated.mapTo(HashSet()) { it.id }
        connections.filterNot { it.id in survivingIds }.forEach { EtcdCredentials.forget(it.id) }
        connections = updated.toMutableList()
        if (connections.none { it.id == lastUsedConnectionId }) lastUsedConnectionId = ""
    }

    companion object {
        fun getInstance(): EtcdSettingsState = service()
    }
}
