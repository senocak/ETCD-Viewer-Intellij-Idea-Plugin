package com.github.senocak.etcd

import com.github.senocak.etcd.ui.kv.EtcdKeyTree
import com.github.senocak.etcd.ui.kv.EtcdTreeNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The incremental updates that keep the key tree in step with a live cluster.
 *
 * These run against the tree model directly — no cluster needed — so the folding, ordering and
 * pruning rules are pinned down independently of the watch that drives them.
 */
class EtcdKeyTreeLiveUpdateTest : BasePlatformTestCase() {

    private lateinit var tree: EtcdKeyTree

    override fun setUp() {
        super.setUp()
        tree = EtcdKeyTree(project) {}
        // Pretend the root has been fetched; otherwise updates are deliberately ignored.
        rootDir().loaded = true
    }

    private fun model() = tree.model as DefaultTreeModel

    private fun root() = model().root as DefaultMutableTreeNode

    private fun rootDir() = root().userObject as EtcdTreeNode.Dir

    private fun childLabels(node: DefaultMutableTreeNode = root()): List<String> =
        (0 until node.childCount).map { (node.getChildAt(it) as DefaultMutableTreeNode).userObject.toString() }

    private fun folder(vararg path: String): DefaultMutableTreeNode {
        var node = root()
        for (name in path) {
            node = (0 until node.childCount)
                .map { node.getChildAt(it) as DefaultMutableTreeNode }
                .first { (it.userObject as? EtcdTreeNode.Dir)?.label == name }
        }
        return node
    }

    fun `test a new key appears as a folder without an unnamed level`() {
        tree.onKeyPut("/app/config/db")

        // The leading slash must not become a folder of its own.
        assertEquals(listOf("app"), childLabels())
        val app = folder("app")
        assertEquals("/app/", (app.userObject as EtcdTreeNode.Dir).prefix)
    }

    fun `test a new key lands in an already loaded folder`() {
        givenLoadedFolder("/app/", "app")

        tree.onKeyPut("/app/one")
        tree.onKeyPut("/app/two")

        assertEquals(listOf("one", "two"), childLabels(folder("app")))
        assertTrue(tree.containsKey("/app/two"))
    }

    /** Adds a folder in the state the page loader would leave it: present and fully fetched. */
    private fun givenLoadedFolder(prefix: String, label: String): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(EtcdTreeNode.Dir(prefix, label).apply { loaded = true })
        root().add(node)
        return node
    }

    fun `test a new key keeps siblings in key order`() {
        val app = givenLoadedFolder("/app/", "app")
        listOf("/app/b", "/app/d").forEach { tree.onKeyPut(it) }

        tree.onKeyPut("/app/c")
        tree.onKeyPut("/app/a")

        // A live insert lands where a refresh would have put it.
        assertEquals(listOf("a", "b", "c", "d"), childLabels(app))
    }

    fun `test an unloaded folder is left alone`() {
        tree.onKeyPut("/app/one")
        val app = folder("app")
        // The folder is collapsed and unfetched, so it keeps its placeholder child.
        assertFalse((app.userObject as EtcdTreeNode.Dir).loaded)

        tree.onKeyPut("/app/deep/nested/key")

        // Nothing was added under it: the key will show up when the user expands it.
        assertFalse(tree.containsKey("/app/deep/nested/key"))
        assertEquals(1, app.childCount)
        assertTrue((app.getChildAt(0) as DefaultMutableTreeNode).userObject is EtcdTreeNode.Unloaded)
    }

    fun `test a deleted key is removed`() {
        givenLoadedFolder("/app/", "app")
        tree.onKeyPut("/app/one")
        tree.onKeyPut("/app/two")

        tree.onKeyDeleted("/app/one")

        assertFalse(tree.containsKey("/app/one"))
        assertTrue(tree.containsKey("/app/two"))
    }

    fun `test deleting the last key prunes the empty folder`() {
        givenLoadedFolder("/app/", "app")
        tree.onKeyPut("/app/only")

        tree.onKeyDeleted("/app/only")

        // An emptied folder should not linger as a dead branch.
        assertTrue("the empty folder should be pruned", childLabels().none { it == "app" })
    }

    fun `test deleting an unknown key is harmless`() {
        tree.onKeyPut("/app/one")
        val before = childLabels()

        tree.onKeyDeleted("/nothing/here")

        assertEquals(before, childLabels())
    }

    fun `test a key outside the browsed prefix is ignored`() {
        val filtered = EtcdKeyTree(project) {}
        filtered.setClient(null, "/app/")
        (filtered.model as DefaultTreeModel).let { m ->
            ((m.root as DefaultMutableTreeNode).userObject as EtcdTreeNode.Dir).loaded = true
        }

        filtered.onKeyPut("/other/key")

        assertFalse(filtered.containsKey("/other/key"))
    }
}
