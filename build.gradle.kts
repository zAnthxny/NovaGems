import java.io.File

plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "net.watones"
version = "1.1.4"
val pluginVersion = version.toString()
val slimArtifact = layout.buildDirectory.file("libs/NovaCoins-$pluginVersion.jar")
val offlineArtifact = layout.buildDirectory.file("libs/NovaCoins-$pluginVersion-offline.jar")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.3")

    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")
    implementation("com.mysql:mysql-connector-j:9.6.0")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

configurations.implementation {
    exclude(group = "org.slf4j", module = "slf4j-api")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    jar {
        enabled = true
        archiveFileName.set("NovaCoins-${project.version}.jar")
        exclude("plugin-offline.yml")
    }
    processResources {
        inputs.property("pluginVersion", pluginVersion)
        filesMatching(listOf("plugin.yml", "plugin-offline.yml")) {
            expand("version" to pluginVersion)
        }
    }
    test { useJUnitPlatform() }
    shadowJar {
        archiveFileName.set("NovaCoins-${project.version}-offline.jar")
        exclude("plugin.yml")
        filesMatching("plugin-offline.yml") { name = "plugin.yml" }
        mergeServiceFiles()
    }

    fun registerArtifactSmoke(name: String, mode: String, artifact: Provider<RegularFile>,
                              includeRuntimeLibraries: Boolean) = register<JavaExec>(name) {
        dependsOn(testClasses, if (mode == "slim") jar else shadowJar)
        classpath = sourceSets.test.get().output
        mainClass.set("net.watones.novacoins.build.ArtifactSmokeMain")
        doFirst {
            val slf4j = configurations.testRuntimeClasspath.get().files
                .filter { it.name.startsWith("slf4j-") }
            val libraries = if (includeRuntimeLibraries) {
                configurations.runtimeClasspath.get().files + slf4j
            } else slf4j
            setArgs(listOf(mode, artifact.get().asFile.absolutePath,
                libraries.joinToString(File.pathSeparator) { it.absolutePath }))
        }
    }

    val verifySlimArtifact = registerArtifactSmoke(
        "verifySlimArtifact", "slim", slimArtifact, true)
    val verifyOfflineArtifact = registerArtifactSmoke(
        "verifyOfflineArtifact", "offline", offlineArtifact, false)
    check { dependsOn(verifySlimArtifact, verifyOfflineArtifact) }
    build { dependsOn(shadowJar) }
}
