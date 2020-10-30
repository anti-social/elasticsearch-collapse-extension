import com.jfrog.bintray.gradle.BintrayExtension
import com.jfrog.bintray.gradle.tasks.RecordingCopyTask
import java.util.Date

buildscript {
    val esVersion = project.properties["esVersion"] ?: "6.8.13"
    repositories {
        jcenter()
    }
    dependencies {
        classpath("org.elasticsearch.gradle:build-tools:$esVersion")
    }
}

plugins {
    java
    idea
    id("org.ajoberstar.grgit") version "4.1.0"
    id("com.jfrog.bintray") version "1.8.5"
}

apply {
    plugin("elasticsearch.esplugin")
}

group = "dev.evo"

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
    name = "collapse-extenstion"
    description = "Adds rescorer for mixing up search hits inside their groups."
    classname = "dev.evo.elasticsearch.collapse.CollapseRescorePlugin"
    version = project.version.toString()
    licenseFile = project.file("LICENSE.txt")
    noticeFile = project.file("NOTICE.txt")
}

configure<org.elasticsearch.gradle.test.ClusterConfiguration> {
    distribution = "default"
}

// Fails with IllegalArgumentException; reason is unknown
tasks.named("loggerUsageCheck") {
    enabled = false
}
// We don't have unit tests yet
tasks.named("testingConventions") {
    enabled = false
}
tasks.named("unitTest") {
    enabled = false
}


bintray {
    user = if (hasProperty("bintrayUser")) {
        property("bintrayUser").toString()
    } else {
        System.getenv("BINTRAY_USER")
    }
    key = if (hasProperty("bintrayApiKey")) {
        property("bintrayApiKey").toString()
    } else {
        System.getenv("BINTRAY_API_KEY")
    }
    pkg(delegateClosureOf<BintrayExtension.PackageConfig> {
        repo = "elasticsearch"
        name = project.name
        userOrg = "evo"
        setLicenses("Apache-2.0")
        setLabels("elasticsearch-plugin", "elasticsearch-collapse-extension")
        vcsUrl = "https://github.com/anti-social/elasticsearch-collapse-extension.git"
        version(delegateClosureOf<BintrayExtension.VersionConfig> {
            name = pluginVersion
            released = Date().toString()
            vcsTag = "v$pluginVersion"
        })
    })
    filesSpec(delegateClosureOf<RecordingCopyTask> {
        val distributionsDir = buildDir.resolve("distributions")
        from(distributionsDir)
        include("*-$pluginVersion-*.zip")
        into(".")
    })
    publish = true
    dryRun = hasProperty("bintrayDryRun")
}
