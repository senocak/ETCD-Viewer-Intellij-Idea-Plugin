package com.github.senocak.etcd.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores etcd passwords in the IDE's PasswordSafe, keyed by connection id.
 *
 * Keeping secrets out of [EtcdSettingsState] means `etcd-connections.xml` can be shared or
 * committed without leaking credentials. Keying on the id rather than the name means renaming a
 * connection does not orphan its password.
 */
object EtcdCredentials {

    private fun attributes(connectionId: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("etcd", connectionId))

    fun getPassword(connectionId: String): String? =
        PasswordSafe.instance.get(attributes(connectionId))?.getPasswordAsString()?.takeIf { it.isNotEmpty() }

    fun setPassword(connectionId: String, username: String, password: String?) {
        val credentials = if (password.isNullOrEmpty()) null else Credentials(username, password)
        PasswordSafe.instance.set(attributes(connectionId), credentials)
    }

    /** Called when a connection is deleted so its secret does not linger in the password store. */
    fun forget(connectionId: String) {
        PasswordSafe.instance.set(attributes(connectionId), null)
    }
}
