buildscript {
    repositories {
        // mavenCentral()
        // maven {
        //     name 'sonatype-snapshots'
        //     url "https://oss.sonatype.org/content/repositories/snapshots/"
        // }
        jcenter()
    }
    dependencies {
        classpath("org.elasticsearch.gradle:build-tools:6.8.12")
    }
}

plugins {
    java
    // id("elasticsearch.esplugin") version "6.7.2"
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
    // testCompile("junit", "junit", "4.12")
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
