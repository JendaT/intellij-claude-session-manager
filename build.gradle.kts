plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Kotlin serialization for JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // IntelliJ Platform - use local WebStorm installation
    intellijPlatform {
        // Compile against a local JetBrains IDE installation.
        // The plugin runs in all JetBrains IDEs (WebStorm, PyCharm, IDEA, CLion, PHPStorm).
        // Override via: ./gradlew build -PlocalIdePath=/path/to/IDE.app
        local(providers.gradleProperty("localIdePath").getOrElse("/Users/jendalen/Applications/WebStorm.app"))

        // Terminal plugin dependency
        bundledPlugin("org.jetbrains.plugins.terminal")

        pluginVerifier()
        zipSigner()
    }

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }  // No upper limit
        }

        vendor {
            name = "Jenda Tresnak"
            url = "https://github.com/jendalen"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    wrapper {
        gradleVersion = "8.13"
    }

    // Skip buildSearchableOptions when using local IDE (known issue)
    buildSearchableOptions {
        enabled = false
    }
}
