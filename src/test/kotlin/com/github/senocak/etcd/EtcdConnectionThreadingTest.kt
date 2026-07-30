package com.github.senocak.etcd

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.service.EtcdConnectionService
import com.github.senocak.etcd.settings.EtcdConnection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Connection changes must reach their listeners on the EDT.
 *
 * `connect` runs on a background thread, and the message bus delivers synchronously on whatever
 * thread publishes — so publishing from there hands every listener, all of which drive Swing, to
 * a non-EDT thread. That surfaces as "TreeUI should be accessed only from EDT" the moment the
 * key browser reacts.
 *
 * This has to drive a real connection: the publish only happens after `testConnection` succeeds.
 * Skipped unless an endpoint is supplied:
 *
 *     ./gradlew test -Detcd.it.url=http://localhost:2379
 */
class EtcdConnectionThreadingTest : BasePlatformTestCase() {

    fun `test connection changes are delivered on the EDT`() {
        val url = System.getProperty("etcd.it.url").orEmpty()
        if (url.isBlank()) {
            println("skipping ${javaClass.simpleName}: set -Detcd.it.url to run it")
            return
        }

        val service = EtcdConnectionService.getInstance(project)
        val deliveredOnEdt = CopyOnWriteArrayList<Boolean>()
        project.messageBus.connect(testRootDisposable).subscribe(
            EtcdConnectionService.TOPIC,
            object : EtcdConnectionService.Listener {
                override fun connectionChanged(client: EtcdClient?) {
                    deliveredOnEdt.add(ApplicationManager.getApplication().isDispatchThread)
                }
            },
        )

        try {
            // Exactly how EtcdTasks invokes it: off the EDT.
            ApplicationManager.getApplication()
                .executeOnPooledThread { service.connect(EtcdConnection("threading-test", url)) }
                .get(20, TimeUnit.SECONDS)

            assertTrue("no connection change was ever delivered", waitForDelivery(deliveredOnEdt, 1))
            assertTrue("connect published off the EDT", deliveredOnEdt.all { it })

            deliveredOnEdt.clear()
            ApplicationManager.getApplication()
                .executeOnPooledThread { service.disconnect() }
                .get(20, TimeUnit.SECONDS)

            assertTrue("no disconnection was ever delivered", waitForDelivery(deliveredOnEdt, 1))
            assertTrue("disconnect published off the EDT", deliveredOnEdt.all { it })
        } finally {
            service.disconnect()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    /** Publishing is now asynchronous, so the queue has to be pumped until it lands. */
    private fun waitForDelivery(sink: List<Boolean>, expected: Int): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (sink.size >= expected) return true
            Thread.sleep(25)
        }
        return sink.size >= expected
    }
}
