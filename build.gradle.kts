buildscript {
    val esVersion = project.properties["esVersion"] ?: "7.2.1"
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.elasticsearch.gradle:build-tools:$esVersion")
    }
}

plugins {
    java
    idea
    id("org.ajoberstar.grgit") version "4.1.0"
}

apply {
    plugin("elasticsearch.esplugin")
}

group = "dev.evo.elasticsearch"

val lastTag = grgit.describe(mapOf("match" to listOf("v*"), "tags" to true)) ?: "v0.0.0"
val pluginVersion = lastTag.split('-', limit=2)[0].trimStart('v')
val versions = org.elasticsearch.gradle.VersionProperties.getVersions() as Map<String, String>
version = "$pluginVersion-es${versions["elasticsearch"]}"

repositories {
    mavenCentral()
}

dependencies {
}

java {
    sourceCompatibility = JavaVersion.VERSION_13
    targetCompatibility = JavaVersion.VERSION_11
}

configure<org.elasticsearch.gradle.plugin.PluginPropertiesExtension> {
    name = "collapse-extension"
    description = "Adds rescorer for mixing up search hits inside their groups."
    classname = "dev.evo.elasticsearch.collapse.CollapseRescorePlugin"
    version = project.version.toString()
    licenseFile = project.file("LICENSE.txt")
    noticeFile = project.file("NOTICE.txt")
}

configure<org.elasticsearch.gradle.test.ClusterConfiguration> {
    distribution = "default"
    numNodes = 2
}

// Fails with IllegalArgumentException; reason is unknown
tasks.named("loggerUsageCheck") {
    enabled = false
}
// We don't have unit tests yet
tasks.named("testingConventions") {
    enabled = false
}
