package io.availe.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

fun applyKmpConvention(project: Project, projectVersion: String) {
    val kotlinMultiplatformExtension = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return

    val generatedCommonKspKotlin = project.layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin")

    kotlinMultiplatformExtension.sourceSets.named("commonMain").configure {
        kotlin.srcDir(generatedCommonKspKotlin)
        dependencies {
            implementation("io.availe:model-ksp-annotations:$projectVersion")
        }
    }

    project.dependencies.add("kspCommonMainMetadata", "io.availe:model-ksp-processor:$projectVersion")

    val kreplicaMetadataConfiguration = project.configurations.getByName("kreplicaMetadata")
    val metadataFilesProvider = project.provider {
        kreplicaMetadataConfiguration.files.joinToString(java.io.File.pathSeparator)
    }
    project.extensions.configure(KspExtension::class.java) {
        arg("kreplica.metadataFiles", metadataFilesProvider)
    }

    project.tasks.matching { task ->
        val taskName = task.name
        val isKotlinCompile = taskName.startsWith("compile") && taskName.contains("Kotlin") && !taskName.contains(
            "Metadata",
            ignoreCase = true
        )
        val isAndroidCompile = taskName == "compileAndroidMain"
        isKotlinCompile || isAndroidCompile
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}