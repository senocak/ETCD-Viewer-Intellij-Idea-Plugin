package com.github.senocak.etcd

import com.github.senocak.etcd.service.EtcdConnectionService
import com.github.senocak.etcd.settings.EtcdConfigurable
import com.github.senocak.etcd.settings.EtcdConnection
import com.github.senocak.etcd.settings.EtcdSettingsState
import com.github.senocak.etcd.ui.EtcdToolWindowPanel
import com.github.senocak.etcd.ui.kv.EtcdKeyTree
import com.github.senocak.etcd.ui.kv.EtcdTreeNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.xmlb.XmlSerializer
import javax.swing.JTabbedPane
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Builds the plugin's UI and services inside a real IDE fixture.
 *
 * The Plugin Verifier checks that `plugin.xml` references classes that exist; this checks that
 * those classes actually construct — catching a broken service registration or a Swing
 * initialisation error that only shows up at runtime.
 */
class EtcdPluginWiringTest : BasePlatformTestCase() {

    fun `test connection service is registered and starts disconnected`() {
        val service = EtcdConnectionService.getInstance(project)
        assertNotNull(service)
        assertFalse(service.isConnected)
        assertNull(service.client)
        // Disconnecting when nothing is connected must be a no-op rather than an error.
        service.disconnect()
        assertFalse(service.isConnected)
    }

    fun `test settings service is registered`() {
        assertNotNull(EtcdSettingsState.getInstance())
    }

    fun `test tool window shows the keys browser`() {
        val panel = EtcdToolWindowPanel(project)
        try {
            // Only the Keys section ships, so it is presented directly rather than in a tab strip.
            assertNotNull("the tool window should contain the key browser", findKeyTree(panel))
            assertNull("a single section needs no tab strip", findTabbedPane(panel))
        } finally {
            panel.dispose()
        }
    }

    fun `test key tree renders labels rather than object identity`() {
        val tree = EtcdKeyTree(project) {}
        val model = tree.model as DefaultTreeModel
        val root = model.root as DefaultMutableTreeNode
        root.removeAllChildren()
        root.add(DefaultMutableTreeNode(EtcdTreeNode.Key("/app/config/db", "db"), false))
        root.add(DefaultMutableTreeNode(EtcdTreeNode.Dir("/app/nodes/", "nodes")))
        model.reload()
        val key = root.getChildAt(0)
        val dir = root.getChildAt(1)

        assertNotNull("the tree must have a cell renderer installed", tree.cellRenderer)
        assertEquals("db", renderText(tree, key))
        assertEquals("nodes", renderText(tree, dir))

        // `Tree` installs its own NodeRenderer, which renders through convertValueToText. If
        // that path is not covered, cells print the node's object identity — the symptom this
        // test exists to prevent.
        assertEquals("db", tree.convertValueToText(key, false, false, true, 1, false))
        assertEquals("nodes", tree.convertValueToText(dir, false, true, false, 2, false))

        // BasicTreeUI caches the renderer it will actually paint with; it must be ours, or the
        // folder and key icons silently go missing.
        assertEquals(EtcdKeyTree.Renderer::class.java, paintingRenderer(tree).javaClass)
    }

    private fun renderText(tree: EtcdKeyTree, node: Any): String {
        val component = tree.cellRenderer!!
            .getTreeCellRendererComponent(tree, node, false, false, true, 0, false)
        return (component as SimpleColoredComponent).getCharSequence(false).toString()
    }

    /** The renderer the tree UI paints with, which is cached separately from `cellRenderer`. */
    private fun paintingRenderer(tree: EtcdKeyTree): Any {
        val field = BasicTreeUI::class.java.getDeclaredField("currentCellRenderer")
        field.isAccessible = true
        return field.get(tree.ui)
    }

    fun `test the about button agrees with the vendor url in plugin xml`() {
        val descriptor = javaClass.getResource("/META-INF/plugin.xml")?.readText()
        assertNotNull("plugin.xml should be on the test classpath", descriptor)
        val declared = Regex("""<vendor[^>]*\burl="([^"]+)"""").find(descriptor!!)?.groupValues?.get(1)
        assertNotNull("plugin.xml should declare a vendor url", declared)

        val panel = EtcdToolWindowPanel(project)
        try {
            // The button cannot read the descriptor at runtime — every API for that is internal
            // as of 262 — so this is what keeps the constant and plugin.xml from drifting apart.
            assertEquals(declared, panel.vendorUrl)
            assertEquals("https://github.com/senocak", panel.vendorUrl)
        } finally {
            panel.dispose()
        }
    }

    fun `test settings configurable builds`() {
        val configurable = EtcdConfigurable()
        assertEquals("etcd", configurable.displayName)
        assertNotNull(configurable.createComponent())
        assertFalse("a freshly reset page is not modified", configurable.isModified)
        configurable.disposeUIResources()
    }

    fun `test a connection survives serialisation without its password`() {
        val original = EtcdConnection("prod", "https://etcd.example.com:2379").apply {
            username = "root"
            caCertPath = "/tmp/ca.pem"
            trustAllCertificates = true
            keySeparator = ":"
            pageSize = 250
        }

        val element = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(element, EtcdConnection::class.java)

        assertEquals(original.id, restored.id)
        assertEquals("prod", restored.name)
        assertEquals("root", restored.username)
        assertEquals("/tmp/ca.pem", restored.caCertPath)
        assertTrue(restored.trustAllCertificates)
        assertEquals(":", restored.keySeparator)
        assertEquals(250, restored.pageSize)
        assertTrue(restored.isSecure)
        // No password field exists on the bean, so no secret can leak into the settings file.
        assertFalse(XmlSerializer.serialize(original).children.any { it.getAttributeValue("name") == "password" })
    }

    fun `test connection validation rejects bad input`() {
        assertNull(EtcdConnection("ok", "http://localhost:2379").validate())
        assertNotNull(EtcdConnection("", "http://localhost:2379").validate())
        assertNotNull(EtcdConnection("ok", "localhost:2379").validate())
        // A client certificate without its key cannot produce a usable key manager.
        assertNotNull(
            EtcdConnection("ok", "https://localhost:2379")
                .apply { clientCertPath = "/tmp/cert.pem" }
                .validate(),
        )
    }

    private fun findTabbedPane(component: java.awt.Component): JTabbedPane? = when (component) {
        is JTabbedPane -> component
        is java.awt.Container -> component.components.firstNotNullOfOrNull { findTabbedPane(it) }
        else -> null
    }

    private fun findKeyTree(component: java.awt.Component): EtcdKeyTree? = when (component) {
        is EtcdKeyTree -> component
        is java.awt.Container -> component.components.firstNotNullOfOrNull { findKeyTree(it) }
        else -> null
    }
}
