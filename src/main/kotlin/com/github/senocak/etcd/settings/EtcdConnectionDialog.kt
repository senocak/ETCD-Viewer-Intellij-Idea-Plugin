package com.github.senocak.etcd.settings

import com.github.senocak.etcd.client.EtcdClient
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Add/edit dialog for a single etcd connection.
 *
 * Edits a copy, so cancelling leaves the stored connection untouched. The password is handled
 * separately from the rest of the form because it lives in PasswordSafe rather than in the
 * serialised settings.
 */
class EtcdConnectionDialog(
    private val project: Project?,
    original: EtcdConnection,
    private val isNew: Boolean,
) : DialogWrapper(project) {

    /** The working copy; [connection] hands it back only after OK. */
    val connection: EtcdConnection = original.copy()

    /** Null means "leave the stored password alone"; empty means "clear it". */
    var password: String? = null
        private set

    private val passwordField = JBPasswordField()
    private val testResultLabel = JLabel(" ")
    private var formPanel: DialogPanel? = null
    /** Kept as the DSL [Cell] rather than the raw component, since `selected` is a Cell predicate. */
    private lateinit var useTlsCheckBox: Cell<JBCheckBox>

    init {
        title = if (isNew) "Add etcd Connection" else "Edit etcd Connection"
        setOKButtonText(if (isNew) "Add" else "Save")
        if (!isNew) {
            passwordField.text = EtcdCredentials.getPassword(connection.id).orEmpty()
        }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        group("Endpoint") {
            row("Name:") {
                textField().bindText(connection::name).align(Align.FILL)
            }
            row("URL:") {
                textField()
                    .bindText(connection::url)
                    .align(Align.FILL)
                    .comment("Client URL of any cluster member, e.g. http://localhost:2379")
            }
        }

        group("Authentication") {
            row("Username:") {
                textField()
                    .bindText(connection::username)
                    .align(Align.FILL)
                    .comment("Leave empty to connect anonymously")
            }
            row("Password:") {
                cell(passwordField).align(Align.FILL)
            }
        }

        group("TLS") {
            row {
                useTlsCheckBox = checkBox("Endpoint uses HTTPS")
                    .comment("Derived from the URL scheme; tick to reveal the certificate options")
                    .applyToComponent { isSelected = connection.isSecure }
            }
            row("CA certificate:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select CA Certificate")
                        .withDescription("PEM file holding the CA that signed the etcd server certificate"),
                    project,
                ).bindText(connection::caCertPath).align(Align.FILL)
            }.visibleIf(useTlsCheckBox.selected)
            row("Client certificate:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Client Certificate")
                        .withDescription("PEM certificate presented to etcd for mutual TLS"),
                    project,
                ).bindText(connection::clientCertPath).align(Align.FILL)
            }.visibleIf(useTlsCheckBox.selected)
            row("Client key:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Client Key")
                        .withDescription("Unencrypted PKCS#8 private key matching the client certificate"),
                    project,
                ).bindText(connection::clientKeyPath)
                    .align(Align.FILL)
                    .comment("PKCS#8 PEM. Convert PKCS#1 keys with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pk8.pem")
            }.visibleIf(useTlsCheckBox.selected)
            row {
                checkBox("Trust all certificates")
                    .bindSelected(connection::trustAllCertificates)
                    .comment("Disables certificate and hostname verification. Development only.")
            }.visibleIf(useTlsCheckBox.selected)
        }

        group("Browser") {
            row("Key separator:") {
                textField()
                    .bindText(connection::keySeparator)
                    .columns(6)
                    .comment("Character the key tree splits on")
            }
            row("Page size:") {
                intTextField(1..100_000)
                    .bindIntText(connection::pageSize)
                    .columns(8)
                    .comment("Keys fetched per request when browsing")
            }
            row("Timeout (ms):") {
                intTextField(100..600_000)
                    .bindIntText(connection::requestTimeoutMs)
                    .columns(8)
            }
        }

        row {
            button("Test Connection") { testConnection() }
            cell(testResultLabel)
        }.topGap(TopGap.SMALL)
    }.also {
        it.border = JBUI.Borders.empty(8)
        formPanel = it
    }

    override fun doValidate(): ValidationInfo? {
        applyForm()
        return connection.validate()?.let { ValidationInfo(it) }
    }

    override fun doOKAction() {
        applyForm()
        password = String(passwordField.password)
        super.doOKAction()
    }

    /**
     * Pushes the Kotlin UI DSL bindings into [connection].
     *
     * `panel {}` writes bound properties only when its `apply()` runs, so validation, OK and the
     * connection test all have to trigger it before reading the model back.
     */
    private fun applyForm() {
        formPanel?.apply()
    }

    /**
     * Connects with the values currently in the form and reports what came back.
     *
     * Uses a throwaway client with the typed-in password rather than the stored one, so the user
     * can verify new credentials before saving them.
     */
    private fun testConnection() {
        applyForm()
        connection.validate()?.let {
            showTestResult(it, MessageType.ERROR)
            return
        }
        val candidate = connection.copy()
        val typedPassword = String(passwordField.password)
        showTestResult("Connecting…", MessageType.INFO)

        object : Task.Backgroundable(project, "Testing etcd connection", true) {
            private var summary: String? = null
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    EtcdClient(candidate) { typedPassword.ifEmpty { null } }.use {
                        val status = it.testConnection()
                        val members = runCatching { it.cluster.memberList().size }.getOrDefault(1)
                        summary = "Connected to etcd ${status.version} — $members member(s), revision ${status.revision}"
                    }
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                }
            }

            override fun onSuccess() {
                failure?.let { showTestResult(it, MessageType.ERROR) }
                    ?: showTestResult(summary.orEmpty(), MessageType.INFO)
            }
        }.queue()
    }

    private fun showTestResult(text: String, type: MessageType) {
        testResultLabel.text = text
        testResultLabel.foreground = type.titleForeground
    }
}
