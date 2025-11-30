package io.availe.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun applyJvmConvention(project: Project, projectVersion: String) {
    val kotlinProjectExtension = project.extensions.getByType(KotlinProjectExtension::class.java)

    val generatedJvmKspKotlin = project.layout.buildDirectory.dir("generated/ksp/main/kotlin")
    val generatedJvmKspKotlinFiles = project.files(generatedJvmKspKotlin)

    kotlinProjectExtension.sourceSets.named("main").configure {
        kotlin.srcDir(generatedJvmKspKotlinFiles)
    }
    generatedJvmKspKotlinFiles.builtBy("kspKotlin")

    project.dependencies.apply {
        add("implementation", "io.availe:model-ksp-annotations:$projectVersion")
        add("ksp", "io.availe:model-ksp-processor:$projectVersion")
    }

    val kreplicaMetadataConfiguration = project.configurations.getByName("kreplicaMetadata")
    val metadataFilesProvider = project.provider {
        kreplicaMetadataConfiguration.files.joinToString(java.io.File.pathSeparator)
    }
    project.extensions.configure(KspExtension::class.java) {
        arg("kreplica.metadataFiles", metadataFilesProvider)
    }

    project.tasks.withType<KotlinCompile> {
        dependsOn("kspKotlin")
    }
}