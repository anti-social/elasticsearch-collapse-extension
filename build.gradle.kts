import java.nio.file.Paths

buildscript {
    val esVersion = project.properties["esVersion"] ?: "7.13.2"
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
    id("nebula.ospackage") version "8.5.6"
}

apply {
    plugin("elasticsearch.esplugin")
    plugin("elasticsearch.testclusters")
}

group = "dev.evo.elasticsearch"

val lastTag = grgit.describe(mapOf("match" to listOf("v*"), "tags" to true)) ?: "v0.0.0"
val pluginVersion = lastTag.split('-', limit=2)[0].trimStart('v')
val versions = org.elasticsearch.gradle.VersionProperties.getVersions() as Map<String, String>
version = "$pluginVersion-es${versions["elasticsearch"]}"

val distDir = Paths.get(buildDir.path, "distributions")

repositories {
    mavenCentral()
}

dependencies {
}

java {
    sourceCompatibility = JavaVersion.VERSION_13
    targetCompatibility = JavaVersion.VERSION_11
}

val pluginName = "collapse-extension"

configure<org.elasticsearch.gradle.plugin.PluginPropertiesExtension> {
    name = pluginName
    description = "Adds rescorer for mixing up search hits inside their groups."
    classname = "dev.evo.elasticsearch.collapse.CollapseRescorePlugin"
    version = project.version.toString()
    licenseFile = project.file("LICENSE.txt")
    noticeFile = project.file("NOTICE.txt")
}

configure<NamedDomainObjectContainer<org.elasticsearch.gradle.testclusters.ElasticsearchCluster>> {
    val integTestCluster = create("integTest") {
        setTestDistribution(org.elasticsearch.gradle.testclusters.TestDistribution.DEFAULT)
        numberOfNodes = 2
        plugin(tasks.named<Zip>("bundlePlugin").get().archiveFile)
    }

    val integTestTask = tasks.register<org.elasticsearch.gradle.test.RestIntegTestTask>("integTest") {
        dependsOn("bundlePlugin")
    }

    tasks.named("check") {
        dependsOn(integTestTask)
    }
}

tasks.named("validateElasticPom") {
    enabled = false
}

tasks.named("assemble") {
    dependsOn("deb")
}

tasks.register("deb", com.netflix.gradle.plugins.deb.Deb::class) {
    dependsOn("bundlePlugin")

    packageName = project.name
    requires("elasticsearch", versions["elasticsearch"])
        .or("elasticsearch-oss", versions["elasticsearch"])

    from(zipTree(tasks["bundlePlugin"].outputs.files.singleFile))

    val esHome = project.properties["esHome"] ?: "/usr/share/elasticsearch"
    into("$esHome/plugins/${pluginName}")

    doLast {
        if (properties.containsKey("assembledInfo")) {
            distDir.resolve("assembled-deb.filename").toFile()
                .writeText(assembleArchiveName())
        }
    }
}
