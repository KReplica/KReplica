package io.availe

import io.availe.gradle.applyJvmConvention
import io.availe.gradle.applyKmpConvention
import org.gradle.api.Plugin
import org.gradle.api.Project

class KReplicaPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val projectVersion = "5.2.0"
        val kreplicaMetadata = target.configurations.create("kreplicaMetadata") {
            isCanBeResolved = true
            isCanBeConsumed = false
            description = "Resolves models.json files from all project dependencies."
        }
        target.configurations.all {
            val n = name.lowercase()
            if (n.endsWith("implementation") || n.endsWith("api")) {
                kreplicaMetadata.extendsFrom(this)
            }
        }
        target.plugins.withId("com.google.devtools.ksp") {
            when {
                target.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") -> applyKmpConvention(
                    target, projectVersion
                )

                target.plugins.hasPlugin("org.jetbrains.kotlin.jvm") -> applyJvmConvention(target, projectVersion)
                else -> target.logger.warn("KReplica: No supported Kotlin plugin found (multiplatform or jvm). No convention applied.")
            }
        }
    }
}
