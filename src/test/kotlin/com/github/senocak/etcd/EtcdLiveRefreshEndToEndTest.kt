package com.github.senocak.etcd

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.settings.EtcdConnection
import com.github.senocak.etcd.ui.kv.EtcdKvPanel
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Proves the Keys tab keeps itself current when someone else changes etcd.
 *
 * The change is made through a second, independent client — standing in for `etcdctl` or another
 * application — so nothing but the watch can tell the panel about it. This is the wiring that a
 * unit test of the tree alone cannot cover.
 *
 * Skipped unless an endpoint is supplied:
 *
 *     ./gradlew test -Detcd.it.url=http://localhost:2379
 */
class EtcdLiveRefreshEndToEndTest : BasePlatformTestCase() {

    private var panelClient: EtcdClient? = null
    private var externalClient: EtcdClient? = null

    override fun tearDown() {
        try {
            externalClient?.let { runCatching { it.kv.deletePrefix(PREFIX) } }
            panelClient?.let { runCatching { it.close() } }
            externalClient?.let { runCatching { it.close() } }
        } finally {
            super.tearDown()
        }
    }

    fun `test an externally added key appears without a manual refresh`() {
        val url = endpointOrSkip() ?: return
        val panel = connectedPanel(url)
        val external = externalClient!!

        external.kv.put("${PREFIX}live/added", "v")

        assertTrue(
            "the key added by another client should appear on its own",
            waitUntil { panel.tree.containsKey("${PREFIX}live/added") },
        )
    }

    fun `test an externally deleted key disappears without a manual refresh`() {
        val url = endpointOrSkip() ?: return
        val panel = connectedPanel(url)
        val external = externalClient!!

        external.kv.put("${PREFIX}live/doomed", "v")
        assertTrue(
            "the key should appear before the test can delete it",
            waitUntil { panel.tree.containsKey("${PREFIX}live/doomed") },
        )

        external.kv.delete("${PREFIX}live/doomed")

        assertTrue(
            "the key removed by another client should disappear on its own",
            waitUntil { !panel.tree.containsKey("${PREFIX}live/doomed") },
        )
    }

    /**
     * Builds a panel browsing [PREFIX] and waits for its watch to come up.
     *
     * Expanding the whole prefix matters: the tree deliberately ignores changes under folders it
     * has not loaded, so the test has to be looking at the branch it asserts on.
     */
    private fun connectedPanel(url: String): EtcdKvPanel {
        val client = EtcdClient(EtcdConnection("panel", url)) { null }
        panelClient = client
        externalClient = EtcdClient(EtcdConnection("external", url)) { null }
        client.kv.deletePrefix(PREFIX)
        client.kv.put("${PREFIX}live/seed", "v")

        val panel = EtcdKvPanel(project)
        panel.setBrowsedPrefix("${PREFIX}live/")
        panel.onConnectionChanged(client)

        assertTrue("the live-update watch never started", waitUntil { panel.isLiveUpdating })
        assertTrue("the browsed folder never loaded", waitUntil { panel.tree.containsKey("${PREFIX}live/seed") })
        return panel
    }

    /** Pumps the event queue until [condition] holds, since updates land on the EDT. */
    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return true
            Thread.sleep(50)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return condition()
    }

    private fun endpointOrSkip(): String? {
        val url = System.getProperty("etcd.it.url").orEmpty()
        if (url.isBlank()) {
            println("skipping ${javaClass.simpleName}: set -Detcd.it.url to run it")
            return null
        }
        return url
    }

    private companion object {
        const val PREFIX = "/claude-integration-test/"
        const val TIMEOUT_MS = 15_000L
    }
}
