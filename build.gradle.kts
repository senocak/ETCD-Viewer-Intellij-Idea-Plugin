import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)
    }
}

// The plugin talks to etcd over its v3 gRPC-gateway (HTTP/JSON) using the JDK's built-in
// java.net.http.HttpClient, so it deliberately has no third-party runtime dependencies.
// EtcdClientIntegrationTest talks to a real cluster and skips itself unless an endpoint is given:
//   ./gradlew test -Detcd.it.url=http://localhost:2379
tasks.test {
    systemProperty("etcd.it.url", providers.systemProperty("etcd.it.url").getOrElse(""))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}
