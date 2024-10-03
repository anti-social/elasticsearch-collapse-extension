import java.nio.file.Paths

buildscript {
    val esVersion = project.properties["esVersion"] ?: "7.9.3"
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.elasticsearch.gradle:build-tools:$esVersion")
        constraints {
            classpath("com.avast.gradle:gradle-docker-compose-plugin:0.14.2")
            classpath("com.netflix.nebula:nebula-core:4.0.1")
        }
    }
}

plugins {
    java
    idea
    id("org.ajoberstar.grgit") version "4.1.1"
    id("nebula.ospackage") version "9.1.1"
}

apply {
    plugin("elasticsearch.esplugin")
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
