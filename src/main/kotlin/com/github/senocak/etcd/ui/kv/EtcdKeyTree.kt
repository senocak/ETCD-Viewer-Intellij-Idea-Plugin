package com.github.senocak.etcd.ui.kv

import com.github.senocak.etcd.client.EtcdClient
import com.github.senocak.etcd.client.KeyUtil
import com.github.senocak.etcd.model.EtcdKeyValue
import com.github.senocak.etcd.service.EtcdTasks
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * What a node in the key tree stands for.
 *
 * [displayText] is also what [toString] returns. Swing wraps these in `DefaultMutableTreeNode`,
 * whose `toString` delegates to the user object, so keeping the two in step means every
 * rendering path — the custom renderer, `convertValueToText`, speed search, accessibility —
 * shows the same label instead of an object identity string.
 */
sealed class EtcdTreeNode {

    abstract val displayText: String

    final override fun toString(): String = displayText

    /** A synthetic folder standing for a shared key prefix. */
    class Dir(val prefix: String, val label: String) : EtcdTreeNode() {
        override val displayText: String get() = label

        /** Where the next page of children starts; null once every child has been loaded. */
        var nextPageFrom: String? = null
        var loaded: Boolean = false
    }

    /** A real etcd key. */
    class Key(val key: String, val label: String) : EtcdTreeNode() {
        override val displayText: String get() = label
    }

    /** Placeholder occupying an unexpanded folder so the tree draws a expand handle. */
    object Unloaded : EtcdTreeNode() {
        override val displayText: String get() = ""
    }

    object Loading : EtcdTreeNode() {
        override val displayText: String get() = "Loading…"
    }

    /** Clickable node that fetches the next page of a folder's children. */
    class LoadMore(val dir: Dir) : EtcdTreeNode() {
        override val displayText: String get() = "Load more…"
    }

    class Message(val text: String) : EtcdTreeNode() {
        override val displayText: String get() = text
    }

    companion object {
        /** The label for a raw tree node, or null when it is not one of ours. */
        fun displayTextOf(value: Any?): String? =
            ((value as? DefaultMutableTreeNode)?.userObject as? EtcdTreeNode)?.displayText
    }
}

/**
 * Browses etcd's flat key space as a tree.
 *
 * etcd has no directories — the hierarchy here is derived by splitting keys on the connection's
 * separator. Folders load lazily one page at a time, so opening a cluster with a very large key
 * space does not stall the UI or drag the whole store over the wire.
 */
class EtcdKeyTree(
    private val project: Project,
    private val onKeySelected: (String?) -> Unit,
) : Tree() {

    private var client: EtcdClient? = null

    private val separator: String get() = client?.connection?.keySeparator?.ifEmpty { "/" } ?: "/"

    private val pageSize: Int get() = client?.connection?.pageSize ?: 500

    /** Prefix the tree is rooted at; the search box narrows it. */
    var rootPrefix: String = ""
        private set

    private val rootNode = DefaultMutableTreeNode(EtcdTreeNode.Dir("", "/"))

    /** Prefixes of folders open before the last [reload], re-expanded as their pages arrive. */
    private val pendingExpansion = LinkedHashSet<String>()

    /** Key to re-select once it reappears after a [reload]. */
    private var pendingSelection: String? = null

    init {
        model = DefaultTreeModel(rootNode)
        isRootVisible = true
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = Renderer()

        addTreeWillExpandListener(object : TreeWillExpandListener {

            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val dir = node.userObject as? EtcdTreeNode.Dir ?: return
                if (!dir.loaded) loadChildren(node, dir, append = false)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })

        addTreeSelectionListener {
            val node = lastSelectedPathComponent as? DefaultMutableTreeNode
            when (val payload = node?.userObject) {
                is EtcdTreeNode.Key -> onKeySelected(payload.key)
                is EtcdTreeNode.LoadMore -> {
                    val parent = node.parent as? DefaultMutableTreeNode
                    if (parent != null) loadChildren(parent, payload.dir, append = true)
                }
                else -> onKeySelected(null)
            }
        }

        // `Tree`'s constructor installs NodeRenderer after BasicTreeUI has already cached a
        // renderer, and the cache is only refreshed when the UI is installed. Reinstalling it
        // once here makes the tree actually paint with [Renderer] — and therefore with our
        // folder and key icons — rather than falling back to NodeRenderer.
        updateUI()
    }

    /**
     * Re-installs the custom renderer every time the look and feel is applied.
     *
     * `Tree`'s own constructor installs `NodeRenderer`, and `BasicTreeUI` caches whichever
     * renderer is present when the UI is installed. Setting ours in `init` alone leaves that
     * cache pointing at `NodeRenderer`, which falls back to [convertValueToText] and prints the
     * node's object identity instead of its label.
     */
    override fun updateUI() {
        super.updateUI()
        cellRenderer = Renderer()
    }

    /**
     * The text every non-custom rendering path uses — `NodeRenderer`, speed search, tooltips
     * and accessibility all route through here.
     */
    override fun convertValueToText(
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): String = EtcdTreeNode.displayTextOf(value)
        ?: super.convertValueToText(value, selected, expanded, leaf, row, hasFocus)

    /** Points the tree at a new connection and reloads from [prefix]. */
    fun setClient(newClient: EtcdClient?, prefix: String = "") {
        client = newClient
        rootPrefix = prefix
        // A different endpoint or filter is a different key space; nothing to carry over.
        pendingExpansion.clear()
        pendingSelection = null
        reload()
    }

    /**
     * Rebuilds the tree from the root, discarding everything already loaded.
     *
     * Which folders were open and which key was selected are remembered and restored as the
     * pages come back, so a refresh does not collapse the tree the user was working in.
     */
    fun reload() {
        rememberViewState()
        val label = rootPrefix.ifEmpty { separator }
        val dir = EtcdTreeNode.Dir(rootPrefix, label)
        rootNode.userObject = dir
        rootNode.removeAllChildren()
        (model as DefaultTreeModel).reload()
        if (client == null) {
            setMessage("Not connected")
            return
        }
        loadChildren(rootNode, dir, append = false)
    }

    /** Selects [key], expanding folders along the way, loading them if necessary. */
    fun selectKey(key: String) {
        expandTo(rootNode, key)
    }

    /**
     * Reflects a key that now exists, without refetching anything.
     *
     * Walks down from the root creating the folders the key needs. A folder that has not been
     * loaded yet is left alone — the key will simply be there when the user expands it — so a
     * busy cluster cannot force the whole tree into memory.
     */
    fun onKeyPut(key: String) {
        if (!key.startsWith(rootPrefix)) return
        val treeModel = model as DefaultTreeModel
        var parent = rootNode
        var parentDir = parent.userObject as? EtcdTreeNode.Dir ?: return

        while (true) {
            if (!parentDir.loaded) return
            val segment = KeyUtil.childSegment(parentDir.prefix, key, separator) ?: return
            if (!segment.isFolder) {
                if (findChildKey(parent, key) == null) {
                    dropPlaceholders(parent, treeModel)
                    insertSorted(
                        parent,
                        DefaultMutableTreeNode(EtcdTreeNode.Key(key, segment.name), false),
                        key,
                        treeModel,
                    )
                }
                return
            }
            val childPrefix = segment.childPrefix
            val existing = findChildDir(parent, childPrefix)
            if (existing != null) {
                parent = existing
                parentDir = existing.userObject as EtcdTreeNode.Dir
                continue
            }
            // A brand new folder: show it collapsed, and let expansion fetch its contents.
            val newDir = EtcdTreeNode.Dir(childPrefix, segment.name)
            val newNode = DefaultMutableTreeNode(newDir)
            newNode.add(DefaultMutableTreeNode(EtcdTreeNode.Unloaded, false))
            dropPlaceholders(parent, treeModel)
            insertSorted(parent, newNode, childPrefix, treeModel)
            return
        }
    }

    /**
     * Reflects a key that no longer exists.
     *
     * Folders left empty by the removal are pruned too, so deleting the last key under a prefix
     * does not leave a dead folder behind.
     */
    fun onKeyDeleted(key: String) {
        val treeModel = model as DefaultTreeModel
        val node = findKeyNode(rootNode, key) ?: return
        var parent = node.parent as? DefaultMutableTreeNode
        treeModel.removeNodeFromParent(node)

        while (parent != null && parent != rootNode) {
            val dir = parent.userObject as? EtcdTreeNode.Dir ?: break
            // Only prune a folder we fully loaded; an unexplored one may still hold keys.
            if (!dir.loaded || parent.childCount > 0) break
            val grandParent = parent.parent as? DefaultMutableTreeNode
            treeModel.removeNodeFromParent(parent)
            parent = grandParent
        }
        if (rootNode.childCount == 0) {
            rootNode.add(DefaultMutableTreeNode(EtcdTreeNode.Message("(empty)"), false))
            treeModel.reload(rootNode)
        }
    }

    /** Whether [key] currently has a node in the tree. */
    fun containsKey(key: String): Boolean = findKeyNode(rootNode, key) != null

    private fun findKeyNode(from: DefaultMutableTreeNode, key: String): DefaultMutableTreeNode? {
        for (i in 0 until from.childCount) {
            val child = from.getChildAt(i) as DefaultMutableTreeNode
            when (val payload = child.userObject) {
                is EtcdTreeNode.Key -> if (payload.key == key) return child
                is EtcdTreeNode.Dir -> if (key.startsWith(payload.prefix)) {
                    findKeyNode(child, key)?.let { return it }
                }

                else -> Unit
            }
        }
        return null
    }

    private fun findChildKey(parent: DefaultMutableTreeNode, key: String): DefaultMutableTreeNode? =
        (0 until parent.childCount)
            .map { parent.getChildAt(it) as DefaultMutableTreeNode }
            .firstOrNull { (it.userObject as? EtcdTreeNode.Key)?.key == key }

    private fun findChildDir(parent: DefaultMutableTreeNode, prefix: String): DefaultMutableTreeNode? =
        (0 until parent.childCount)
            .map { parent.getChildAt(it) as DefaultMutableTreeNode }
            .firstOrNull { (it.userObject as? EtcdTreeNode.Dir)?.prefix == prefix }

    /**
     * Inserts [child] keeping siblings in the key order etcd returns them in, so a live insert
     * lands where a refresh would have put it. "Load more" and message nodes stay last.
     */
    private fun insertSorted(
        parent: DefaultMutableTreeNode,
        child: DefaultMutableTreeNode,
        sortKey: String,
        treeModel: DefaultTreeModel,
    ) {
        var index = parent.childCount
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChildAt(i) as DefaultMutableTreeNode
            val siblingKey = when (val payload = sibling.userObject) {
                is EtcdTreeNode.Dir -> payload.prefix
                is EtcdTreeNode.Key -> payload.key
                else -> {
                    index = i
                    break
                }
            }
            if (siblingKey > sortKey) {
                index = i
                break
            }
        }
        treeModel.insertNodeInto(child, parent, index)
    }

    private fun dropPlaceholders(parent: DefaultMutableTreeNode, treeModel: DefaultTreeModel) {
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            if (child.userObject is EtcdTreeNode.Message || child.userObject is EtcdTreeNode.Unloaded) {
                treeModel.removeNodeFromParent(child)
            }
        }
    }

    private fun rememberViewState() {
        pendingExpansion.clear()
        for (row in 0 until rowCount) {
            val path = getPathForRow(row) ?: continue
            if (!isExpanded(path)) continue
            val dir = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? EtcdTreeNode.Dir
                ?: continue
            pendingExpansion.add(dir.prefix)
        }
        pendingSelection = selectedKey()
    }

    /** Re-opens folders and re-selects the key that were live before the last [reload]. */
    private fun restoreViewState(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            when (val payload = child.userObject) {
                is EtcdTreeNode.Dir -> if (payload.prefix in pendingExpansion) {
                    expandPath(TreePath(child.path))
                }

                is EtcdTreeNode.Key -> if (payload.key == pendingSelection) {
                    val path = TreePath(child.path)
                    selectionPath = path
                    scrollPathToVisible(path)
                    pendingSelection = null
                }

                else -> Unit
            }
        }
    }

    /** The key currently selected, or null when a folder or placeholder is selected. */
    fun selectedKey(): String? =
        ((lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? EtcdTreeNode.Key)?.key

    /** The prefix currently selected — a folder's prefix, or the parent folder of a key. */
    fun selectedPrefix(): String? {
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return when (val payload = node.userObject) {
            is EtcdTreeNode.Dir -> payload.prefix
            is EtcdTreeNode.Key -> ((node.parent as? DefaultMutableTreeNode)?.userObject as? EtcdTreeNode.Dir)?.prefix
            else -> null
        }
    }

    private fun setMessage(text: String) {
        rootNode.removeAllChildren()
        rootNode.add(DefaultMutableTreeNode(EtcdTreeNode.Message(text), false))
        (model as DefaultTreeModel).reload()
    }

    /**
     * Fetches one page of children for [dir].
     *
     * With [append] the page continues after the last key already loaded, which is what the
     * "Load more" node triggers; otherwise the folder is refilled from the start.
     */
    private fun loadChildren(node: DefaultMutableTreeNode, dir: EtcdTreeNode.Dir, append: Boolean) {
        val active = client ?: return
        val treeModel = model as DefaultTreeModel

        if (!append) {
            node.removeAllChildren()
            node.add(DefaultMutableTreeNode(EtcdTreeNode.Loading, false))
            treeModel.reload(node)
        } else {
            removePlaceholders(node, treeModel)
        }

        val startFrom = if (append) dir.nextPageFrom else null
        val prefix = dir.prefix
        val limit = pageSize

        EtcdTasks.run(
            project = project,
            title = "Loading etcd keys under '${prefix.ifEmpty { separator }}'",
            work = {
                val (prefixKey, rangeEnd) = KeyUtil.prefixRange(prefix)
                // Paging resumes at the byte after the last key seen, which is that key + \0.
                val fromKey = startFrom?.let { KeyUtil.encode(it.toByteArray(Charsets.UTF_8) + 0) } ?: prefixKey
                active.kv.range(fromKey, rangeEnd, limit = limit, keysOnly = true)
            },
            onSuccess = { result ->
                dir.loaded = true
                applyPage(node, dir, result.kvs, result.more, treeModel)
            },
            onError = { error ->
                dir.loaded = false
                node.removeAllChildren()
                node.add(DefaultMutableTreeNode(EtcdTreeNode.Message(error.message ?: "Failed to load"), false))
                treeModel.reload(node)
                EtcdTasks.notifyError(project, "Loading etcd keys", error)
            },
        )
    }

    private fun applyPage(
        node: DefaultMutableTreeNode,
        dir: EtcdTreeNode.Dir,
        kvs: List<EtcdKeyValue>,
        more: Boolean,
        treeModel: DefaultTreeModel,
    ) {
        removePlaceholders(node, treeModel)

        val existingDirs = HashSet<String>()
        val existingKeys = HashSet<String>()
        for (i in 0 until node.childCount) {
            when (val payload = (node.getChildAt(i) as DefaultMutableTreeNode).userObject) {
                is EtcdTreeNode.Dir -> existingDirs.add(payload.prefix)
                is EtcdTreeNode.Key -> existingKeys.add(payload.key)
                else -> Unit
            }
        }

        for (kv in kvs) {
            val key = kv.keyText
            val segment = KeyUtil.childSegment(dir.prefix, key, separator) ?: continue
            if (segment.isFolder) {
                if (!existingDirs.add(segment.childPrefix)) continue
                val childDir = EtcdTreeNode.Dir(segment.childPrefix, segment.name)
                val childNode = DefaultMutableTreeNode(childDir)
                // A placeholder child is what makes the folder show an expand handle.
                childNode.add(DefaultMutableTreeNode(EtcdTreeNode.Unloaded, false))
                node.add(childNode)
            } else {
                if (!existingKeys.add(key)) continue
                node.add(DefaultMutableTreeNode(EtcdTreeNode.Key(key, segment.name), false))
            }
        }

        dir.nextPageFrom = kvs.lastOrNull()?.keyText.takeIf { more }
        if (more) node.add(DefaultMutableTreeNode(EtcdTreeNode.LoadMore(dir), false))
        if (node.childCount == 0) node.add(DefaultMutableTreeNode(EtcdTreeNode.Message("(empty)"), false))

        treeModel.reload(node)
        expandPath(TreePath(node.path))
        // Re-open whatever was open before a refresh; each expansion loads its own page in turn.
        restoreViewState(node)
    }

    private fun removePlaceholders(node: DefaultMutableTreeNode, treeModel: DefaultTreeModel) {
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            when (child.userObject) {
                is EtcdTreeNode.Loading,
                is EtcdTreeNode.Unloaded,
                is EtcdTreeNode.LoadMore,
                is EtcdTreeNode.Message,
                -> treeModel.removeNodeFromParent(child)

                else -> Unit
            }
        }
    }

    /**
     * Walks down to [key], loading each folder on the way.
     *
     * Loading is asynchronous, so this re-enters itself from the load callback rather than
     * blocking; each pass descends one more level.
     */
    private fun expandTo(node: DefaultMutableTreeNode, key: String) {
        val dir = node.userObject as? EtcdTreeNode.Dir ?: return
        if (!dir.loaded) {
            loadChildrenThen(node, dir) { expandTo(node, key) }
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            when (val payload = child.userObject) {
                is EtcdTreeNode.Key -> if (payload.key == key) {
                    val path = TreePath(child.path)
                    selectionPath = path
                    scrollPathToVisible(path)
                    return
                }

                is EtcdTreeNode.Dir -> if (key.startsWith(payload.prefix)) {
                    expandPath(TreePath(child.path))
                    expandTo(child, key)
                    return
                }

                else -> Unit
            }
        }
    }

    private fun loadChildrenThen(node: DefaultMutableTreeNode, dir: EtcdTreeNode.Dir, then: () -> Unit) {
        val active = client ?: return
        val treeModel = model as DefaultTreeModel
        // Read the page size here, on the EDT, rather than from the background lambda.
        val limit = pageSize
        EtcdTasks.run(
            project = project,
            title = "Loading etcd keys",
            work = {
                val (prefixKey, rangeEnd) = KeyUtil.prefixRange(dir.prefix)
                active.kv.range(prefixKey, rangeEnd, limit = limit, keysOnly = true)
            },
            onSuccess = { result ->
                dir.loaded = true
                applyPage(node, dir, result.kvs, result.more, treeModel)
                then()
            },
        )
    }

    /** Internal rather than private so the wiring test can assert the tree paints with it. */
    internal class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (val payload = (value as? DefaultMutableTreeNode)?.userObject) {
                is EtcdTreeNode.Dir -> {
                    icon = AllIcons.Nodes.Folder
                    append(payload.displayText)
                }

                is EtcdTreeNode.Key -> {
                    icon = AllIcons.FileTypes.Text
                    append(payload.displayText)
                }

                is EtcdTreeNode.LoadMore -> {
                    icon = AllIcons.Actions.MoreHorizontal
                    append(payload.displayText, SimpleTextAttributes.LINK_ATTRIBUTES)
                }

                is EtcdTreeNode -> append(payload.displayText, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                else -> append(value?.toString().orEmpty())
            }
        }
    }
}
