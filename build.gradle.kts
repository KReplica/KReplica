plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.versions)
    alias(libs.plugins.versionCatalogUpdate)
    `maven-publish`
}

allprojects {
    group = "io.availe"
    version = "5.0.3"
}

subprojects {
    apply(plugin = "com.github.ben-manes.versions")
    apply(plugin = "maven-publish")
}

subprojects {
    pluginManager.apply {
        withPlugin("org.jetbrains.kotlin.jvm") { apply("com.vanniktech.maven.publish") }
        withPlugin("org.jetbrains.kotlin.multiplatform") { apply("com.vanniktech.maven.publish") }
        withPlugin("java-gradle-plugin") { apply("com.vanniktech.maven.publish") }
    }
}

tasks.register("checkDependencyUpdates") {
    group = "versioning"
    description = "Checks for dependency updates (does not apply them)."

    dependsOn(":dependencyUpdates")
}

publishing {
    repositories {
        mavenLocal()
    }
}
