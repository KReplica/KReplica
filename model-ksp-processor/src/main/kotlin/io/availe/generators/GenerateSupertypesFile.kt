package io.availe.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.*
import io.availe.builders.asClassName
import io.availe.builders.overwriteFile
import io.availe.models.DtoVariant
import io.availe.models.Model

internal fun generateSupertypesFile(
    models: List<Model>,
    codeGenerator: CodeGenerator,
    dependencies: Dependencies
) {
    val uniqueSupertypes = models.flatMap { it.supertypes }.distinctBy { it.fullyQualifiedName }

    if (uniqueSupertypes.isEmpty()) {
        return
    }

    val representativeSupertype = uniqueSupertypes.first()
    val packageName = representativeSupertype.fullyQualifiedName.substringBeforeLast('.')
    val fileName = "_KReplicaGeneratedSupertypes"

    val fileSpecBuilder = FileSpec.builder(packageName, fileName)
        .addAnnotation(
            AnnotationSpec.builder(Suppress::class)
                .addMember("%S, %S", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                .build()
        )

    uniqueSupertypes.forEach { supertypeInfo ->
        val baseClassName = supertypeInfo.fullyQualifiedName.asClassName()

        DtoVariant.entries.forEach { variant ->
            val variantInterfaceName = baseClassName.simpleName + variant.suffix
            val variantInterfaceSpec = TypeSpec.interfaceBuilder(variantInterfaceName)
                .addModifiers(KModifier.PUBLIC, KModifier.SEALED)
                .addSuperinterface(baseClassName)
                .apply {
                    if (supertypeInfo.isSerializable) {
                        addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
                    }
                }
                .build()
            fileSpecBuilder.addType(variantInterfaceSpec)
        }
    }

    overwriteFile(fileSpecBuilder.build(), codeGenerator, dependencies)
}