buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath("org.elasticsearch.gradle:build-tools:6.8.12")
    }
}

plugins {
    java
}

apply {
    plugin("elasticsearch.esplugin")
}

group = "dev.evo"
version = "1.0-SNAPSHOT"

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
    name = "collapse-rescore"
    description = "Adds rescorer for mixing up search hits inside their groups."
    classname = "dev.evo.elasticsearch.collapse.CollapseRescorePlugin"
    version = project.version.toString()
    licenseFile = project.file("LICENSE.txt")
    noticeFile = project.file("NOTICE.txt")
}

configure<org.elasticsearch.gradle.test.ClusterConfiguration> {
    distribution = "default"
}
