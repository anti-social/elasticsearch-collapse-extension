buildscript {
    val esVersion = project.properties["esVersion"] ?: "7.9.3"
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

configure<NamedDomainObjectContainer<org.elasticsearch.gradle.testclusters.ElasticsearchCluster>> {
    val integTestCluster = named("integTest") {
        setTestDistribution(org.elasticsearch.gradle.testclusters.TestDistribution.DEFAULT)
        numberOfNodes = 2
    }

    tasks.named<org.elasticsearch.gradle.testclusters.RestTestRunnerTask>("integTestRunner") {
        useCluster(integTestCluster.get())
    }
}

tasks.named("licenseHeaders") {
    enabled = false
}

tasks.named("validateNebulaPom") {
    enabled = false
}

// We don't have unit tests yet
tasks.named("testingConventions") {
    enabled = false
}
