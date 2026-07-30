package com.github.senocak.etcd.ui

import com.github.senocak.etcd.client.EtcdClient
import javax.swing.JComponent

/**
 * One tab of the etcd tool window.
 *
 * [EtcdToolWindowPanel] drives the lifecycle: it pushes the active client in on connect, clears
 * it on disconnect, and calls [onActivated] when the tab becomes visible so tabs can defer their
 * first load until someone actually looks at them.
 */
interface EtcdTab {

    val title: String

    val component: JComponent

    /** The connection changed. [client] is null when there is nothing connected. */
    fun onConnectionChanged(client: EtcdClient?)

    /** The tab was brought to the front. */
    fun onActivated() = Unit
}
