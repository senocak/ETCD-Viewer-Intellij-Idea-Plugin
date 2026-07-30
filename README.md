# Etcd

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Connect repository to GitHub

1. [Create a new repository](https://github.com/new) on GitHub.
2. Run the following commands to initialize and push this project to the repository created in step 1:

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<username>/<repository>.git
git push -u origin main
```

3. Configure publishing [secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets) in the GitHub repository settings:

| Secret                 | Description                                                                                                |
|------------------------|------------------------------------------------------------------------------------------------------------|
| `PUBLISH_TOKEN`        | JetBrains Marketplace token — [generate here](https://plugins.jetbrains.com/author/me/tokens)              |
| `CERTIFICATE_CHAIN`    | Plugin signing certificate chain ([docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)) |
| `PRIVATE_KEY`          | Plugin signing private key                                                                                 |
| `PRIVATE_KEY_PASSWORD` | Password for the private key                                                                               |

## Overview

An IntelliJ Platform plugin for browsing and editing an [etcd](https://etcd.io) v3 cluster
without leaving the IDE.

## Functionality

The plugin adds an `etcd` tool window with a connection picker and five tabs:

| Tab         | What it does                                                                                                  |
|-------------|---------------------------------------------------------------------------------------------------------------|
| **Keys**    | Browse the key space as a lazily-loaded prefix tree; read, edit, create and delete keys; delete or export a prefix |
| **Watch**   | Stream live PUT/DELETE events for a key or prefix                                                             |
| **Leases**  | Grant, renew, inspect and revoke leases, with the keys attached to each                                       |
| **Cluster** | Member list, endpoint status, alarms, compaction and defragmentation                                          |
| **Auth**    | Manage users, roles and permissions; enable or disable authentication                                         |

Saving a value uses a compare-and-set on the key's mod revision, so a save never silently
overwrites a change someone else made since the value was loaded. Destructive operations —
prefix delete, compaction, defragmentation, member removal, disabling auth — always confirm
first, and a prefix delete tells you how many keys it matched before you commit.

Connections are configured under **Settings → Tools → etcd**, or via the `Connections…` button
in the tool window. Passwords are stored in the IDE's PasswordSafe, never in the settings file.

### How it connects

The plugin speaks etcd's v3 [gRPC-gateway](https://etcd.io/docs/v3.5/dev-guide/api_grpc_gateway/)
(HTTP/JSON) over the JDK's built-in `java.net.http.HttpClient`, so it bundles **no third-party
libraries** — the distribution is a single ~280 KB jar. The gateway is enabled by default in
etcd 3.4+.

Supported authentication: anonymous, username/password, TLS with a custom CA (or a
development-only "trust all" toggle), and client certificates for mTLS. Client keys must be
unencrypted PKCS#8; convert a PKCS#1 key with
`openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pk8.pem`.

## Testing

`./gradlew build` runs the offline unit and IDE-fixture tests.

`EtcdClientIntegrationTest` exercises the client against a real cluster and skips itself unless
an endpoint is supplied. It writes only under `/claude-integration-test/` and cleans up after
itself:

```bash
docker run -d --name etcd -p 2379:2379 quay.io/coreos/etcd:v3.6.7 \
  /usr/local/bin/etcd --advertise-client-urls http://0.0.0.0:2379 \
    --listen-client-urls http://0.0.0.0:2379

./gradlew test -Detcd.it.url=http://localhost:2379
```

## Plugin structure

A generated project contains the following content structure:

```
.
├── .github/                GitHub Workflows, issue templates, and Dependabot configuration
├── .run/                   Predefined Run/Debug Configurations
├── gradle
│   ├── wrapper/            Gradle Wrapper
│   ├── libs.versions.toml  Version catalog
├── src                     Plugin sources
│   └── main
│       ├── kotlin/         Kotlin production sources
│       └── resources/      Plugin resources
│           ├── META-INF/   Plugin configuration file and logo
│           └── messages/   Message bundles
├── .gitignore              Git ignoring rules
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
├── README.md               This file
└── settings.gradle.kts     Gradle project settings
```

In addition to the configuration files, the most crucial part is the `src` directory, which contains our implementation and the manifest for our plugin – [plugin.xml][file:plugin.xml].

> [!NOTE]
> To use Java in your plugin, create the `/src/main/java` directory.

The plugin logo is placed in `src/main/resources/META-INF/pluginIcon.svg`.
See [Plugin Logo][docs:logo] for more information and logo requirements.

## Build script

The [build.gradle.kts][file:build.gradle.kts] is the core of the project definition.
It applies three Gradle plugins:

| Plugin                             | Description                                                                      |
|------------------------------------|----------------------------------------------------------------------------------|
| `org.jetbrains.kotlin.jvm`         | Adds Kotlin support                                                              |
| `org.jetbrains.changelog`          | Simplifies patching the [CHANGELOG.md][file:CHANGELOG.md] file                   |
| `org.jetbrains.intellij.platform`  | The [IntelliJ Platform Gradle Plugin][docs:intellij-platform-gradle-plugin-docs] |

The `intellijPlatform` dependencies block selects the IDE to compile against:

```kotlin
intellijIdea("2025.3.5")
```

See [Target Versions][docs:target-version] for more information.

The `intellijPlatform` dependencies block also contains a dependency on the platform testing framework:

```kotlin
testFramework(TestFrameworkType.Platform)
```

See [Testing][docs:testing] for more information

## Plugin configuration file

The plugin configuration file is a [plugin.xml][file:plugin.xml] file located in the `src/main/resources/META-INF` directory.
It provides general information about the plugin, its dependencies, extensions, and listeners.

You can read more about this file in the [Plugin Configuration File][docs:plugin.xml] section of our documentation.

### Plugin ID and name

Generated plugin ID and name may require adjustment.

These values are generated based on _Group ID_ and _Artifact ID_ provided in the IDE Plugin wizard.
It is recommended to review `<id>` and `<name>` elements in the plugin.xml file, and adjust them if needed.

Please note that Gradle properties `rootProject.name` and `project.group` don't need to match the `<id>` and `<name>` elements.
There is no IntelliJ Platform-related reason they should as they serve different functions.

## Predefined Run/Debug configurations

Within the default project structure, there is a `.run` directory provided containing predefined *Run/Debug configurations* that expose corresponding Gradle tasks:

| Configuration name  | Description                                                                                                                                                                         |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run IDE with Plugin | Runs [`:runIde`][docs:intellij-platform-gradle-plugin-runIde] IntelliJ Platform Gradle Plugin task. Use the *Debug* icon for plugin debugging.                                        |
| Run Tests           | Runs [`:check`][gradle:lifecycle-tasks] Gradle task.                                                                                                                                |
| Run Verifications   | Runs [`:verifyPlugin`][docs:intellij-platform-gradle-plugin-verifyPlugin] IntelliJ Platform Gradle Plugin task to check the plugin compatibility against the specified IntelliJ IDEs. |

> [!NOTE]
> You can find the logs from the running task in the `idea.log` tab.

## Publishing the plugin

> [!TIP]
> Make sure to follow all guidelines listed in [Publishing a Plugin][docs:publishing] to follow all recommended and required steps.

Releasing a plugin to [JetBrains Marketplace](https://plugins.jetbrains.com) is a straightforward operation that uses the `publishPlugin` Gradle task provided by the [intellij-platform-gradle-plugin][docs:intellij-platform-gradle-plugin-docs].

You can also upload the plugin to the [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload) manually via UI.

## GitHub Integration

### GitHub Actions

The project includes [GitHub Actions][https://docs.github.com/en/actions] workflows for automated CI/CD:

| Workflow | Trigger | Description |
|---|---|---|
| [Build](.github/workflows/build.yml) | Push / PR | Builds, tests, and verifies the plugin; creates a draft release |
| [Release](.github/workflows/release.yml) | GitHub Release | Publishes the plugin to JetBrains Marketplace |

### GitHub issue templates

The project includes GitHub issue templates:
- [Bug Report](.github/ISSUE_TEMPLATE/bug-report.yml)
- [Feature Request](.github/ISSUE_TEMPLATE/feature-request.yml)

See [Syntax for issue forms](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms).

### Dependabot

[Dependabot configuration](.github/dependabot.yml) file enables tracking outdated or vulnerable dependencies.


## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [IntelliJ Platform Gradle Plugin Documentation][docs:intellij-platform-gradle-plugin-docs]
- [IntelliJ Platform Explorer][jb:ipe]
- [JetBrains Marketplace Quality Guidelines][jb:quality-guidelines]
- [IntelliJ Platform UI Guidelines][jb:ui-guidelines]
- [JetBrains Marketplace Paid Plugins][jb:paid-plugins]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij
[docs:plugin.xml]: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html?from=IJPluginReadmeFile
[docs:publishing]: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html?from=IJPluginReadmeFile
[docs:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#runIde
[docs:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html?from=IJPluginReadmeFile#verifyPlugin
[docs:logo]: https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html?from=IJPluginReadmeFile
[docs:target-version]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#target-versions
[docs:testing]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html?from=IJPluginReadmeFile#testing

[file:build.gradle.kts]: ./build.gradle.kts
[file:CHANGELOG.md]: ./CHANGELOG.md
[file:gradle.properties]: ./gradle.properties
[file:plugin.xml]: ./src/main/resources/META-INF/plugin.xml

[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples

[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks

[jb:github]: https://github.com/JetBrains/.github/blob/main/profile/README.md
[jb:forum]: https://platform.jetbrains.com/
[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html
[jb:paid-plugins]: https://plugins.jetbrains.com/docs/marketplace/paid-plugins-marketplace.html
[jb:ipe]: https://jb.gg/ipe
[jb:ui-guidelines]: https://jetbrains.github.io/ui
