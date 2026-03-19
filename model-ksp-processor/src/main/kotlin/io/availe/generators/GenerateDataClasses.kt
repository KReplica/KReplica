package io.availe.generators

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.availe.SCHEMA_SUFFIX
import io.availe.SERIALIZABLE_QUALIFIED_NAME
import io.availe.builders.asClassName
import io.availe.builders.buildAnnotationSpec
import io.availe.builders.buildDataTransferObjectClass
import io.availe.builders.overwriteFile
import io.availe.generators.serializers.PatchSerializerCollector
import io.availe.models.*
import io.availe.validation.fieldsFor

private const val TOP_LEVEL_CLASS_KDOC: String =
    "A sealed interface hierarchy representing all versions of the %L data model."
private const val INTERNAL_SCHEMAS_FILE_NAME = "_InternalKReplicaSchemas"

internal fun generatePublicSchemas(
    primaryModels: List<Model>,
    allModels: List<Model>,
    codeGenerator: CodeGenerator,
    dependencies: Dependencies
) {
    val modelsByBaseName = allModels.groupBy { it.isVersionOf ?: it.name }
    val primaryModelsByBaseName = primaryModels.groupBy { it.isVersionOf ?: it.name }

    primaryModelsByBaseName.forEach { (baseName, versions) ->
        generatePublicSchemaFile(baseName, versions, modelsByBaseName, codeGenerator, dependencies)
    }
}

internal fun generateInternalSchemasFile(
    primaryModels: List<Model>,
    allModels: List<Model>,
    codeGenerator: CodeGenerator,
    dependencies: Dependencies
) {
    if (primaryModels.isEmpty()) return

    val modelsByBaseName = allModels.groupBy { it.isVersionOf ?: it.name }
    val primaryModelsByBaseName = primaryModels.groupBy { it.isVersionOf ?: it.name }
    val representativeModel = primaryModels.first()
    val packageName = representativeModel.packageName

    val collector = PatchSerializerCollector(packageName, "Internal")

    val fileSpec = FileSpec.builder(packageName, INTERNAL_SCHEMAS_FILE_NAME).apply {
        addAnnotation(
            AnnotationSpec.builder(Suppress::class)
                .addMember("%S, %S", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                .build()
        )
        addImport("kotlinx.serialization.builtins", "serializer", "ListSerializer", "SetSerializer", "MapSerializer")

        primaryModelsByBaseName.forEach { (baseName, versions) ->
            val model = versions.first()
            val schemaTypeSpec = if (model.isVersionOf != null) {
                buildVersionedSchema(baseName, versions, modelsByBaseName, KModifier.INTERNAL, collector)
            } else {
                buildUnversionedSchema(baseName, model, modelsByBaseName, KModifier.INTERNAL, collector)
            }
            addType(schemaTypeSpec)
        }

        collector.generatedSerializers().forEach { addType(it) }
    }.build()

    overwriteFile(fileSpec, codeGenerator, dependencies)
}

private fun generatePublicSchemaFile(
    baseName: String,
    versions: List<Model>,
    modelsByBaseName: Map<String, List<Model>>,
    codeGenerator: CodeGenerator,
    dependencies: Dependencies
) {
    val representativeModel = versions.first()
    val schemaFileName = (representativeModel.isVersionOf ?: representativeModel.name) + SCHEMA_SUFFIX
    val collector = PatchSerializerCollector(representativeModel.packageName, baseName)

    val fileSpec = FileSpec.builder(representativeModel.packageName, schemaFileName).apply {
        addAnnotation(
            AnnotationSpec.builder(Suppress::class)
                .addMember("%S, %S", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                .build()
        )
        addImport("kotlinx.serialization.builtins", "serializer", "ListSerializer", "SetSerializer", "MapSerializer")
        if (representativeModel.isVersionOf != null) {
            val schemaSpec = buildVersionedSchema(baseName, versions, modelsByBaseName, KModifier.PUBLIC, collector)
            addType(schemaSpec)
        } else {
            val schemaSpec =
                buildUnversionedSchema(baseName, versions.first(), modelsByBaseName, KModifier.PUBLIC, collector)
            addType(schemaSpec)
        }

        collector.generatedSerializers().forEach { addType(it) }
    }.build()

    overwriteFile(fileSpec, codeGenerator, dependencies)
}

private fun buildVersionedSchema(
    baseName: String,
    versions: List<Model>,
    modelsByBaseName: Map<String, List<Model>>,
    visibility: KModifier,
    collector: PatchSerializerCollector
): TypeSpec {
    val representativeModel = versions.first()
    val schemaFileName = (representativeModel.isVersionOf ?: representativeModel.name) + SCHEMA_SUFFIX
    val isGloballySerializable =
        representativeModel.annotationConfigs.any { it.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME }
    val packageName = representativeModel.packageName
    val supertypesFullyQualifiedNames = representativeModel.supertypes

    return TypeSpec.interfaceBuilder(schemaFileName).apply {
        addModifiers(KModifier.SEALED, visibility)
        supertypesFullyQualifiedNames.forEach {
            addSuperinterface(it.fullyQualifiedName.asClassName())
        }

        addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

        val allVariants = versions.flatMap { it.dtoVariants }.toSet()
        allVariants.forEach { variant ->
            val interfaceName = "${variant.suffix}Variant"
            val variantInterfaceBuilder = TypeSpec.interfaceBuilder(interfaceName).apply {
                addModifiers(KModifier.SEALED)
                addSuperinterface(ClassName(packageName, schemaFileName))
            }.build()
            addType(variantInterfaceBuilder)
        }

        versions.forEach { version ->
            val dataTransferObjects = generateDataTransferObjects(version, modelsByBaseName, collector)
            val versionClass = TypeSpec.interfaceBuilder(version.name).apply {
                addModifiers(KModifier.SEALED)
                addSuperinterface(ClassName(packageName, schemaFileName))
                version.annotations.forEach { addAnnotation(buildAnnotationSpec(it)) }
                if (version.annotationConfigs.any { it.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME }) {
                    addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
                }
                addTypes(dataTransferObjects)
            }.build()
            addType(versionClass)
        }
    }.build()
}

private fun buildUnversionedSchema(
    baseName: String,
    model: Model,
    modelsByBaseName: Map<String, List<Model>>,
    visibility: KModifier,
    collector: PatchSerializerCollector
): TypeSpec {
    val schemaFileName = model.name + SCHEMA_SUFFIX
    val schemaInterfaceName = ClassName(model.packageName, schemaFileName)
    val isGloballySerializable =
        model.annotationConfigs.any { it.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME }
    val supertypesFullyQualifiedNames = model.supertypes

    return TypeSpec.interfaceBuilder(schemaInterfaceName).apply {
        addModifiers(KModifier.SEALED, visibility)
        supertypesFullyQualifiedNames.forEach {
            addSuperinterface(it.fullyQualifiedName.asClassName())
        }

        model.annotations.forEach { addAnnotation(buildAnnotationSpec(it)) }

        addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

        val dataTransferObjects = generateDataTransferObjects(model, modelsByBaseName, collector)
        dataTransferObjects.forEach { dtoSpec ->
            val dtoBuilder = dtoSpec.toBuilder().apply {
                addSuperinterface(schemaInterfaceName)
                DtoVariant.entries.find { it.suffix == dtoSpec.name }?.let { variant ->
                    val globalVariantInterfaceBase = when (variant) {
                        DtoVariant.DATA -> KReplicaDataVariant::class.asClassName()
                        DtoVariant.CREATE -> KReplicaCreateVariant::class.asClassName()
                        DtoVariant.PATCH -> KReplicaPatchVariant::class.asClassName()
                    }
                    val parameterizedGlobalVariant = globalVariantInterfaceBase.parameterizedBy(schemaInterfaceName)
                    addSuperinterface(parameterizedGlobalVariant)
                }
            }.build()
            addType(dtoBuilder)
        }
    }.build()
}

private fun generateDataTransferObjects(
    model: Model,
    modelsByBaseName: Map<String, List<Model>>,
    collector: PatchSerializerCollector
): List<TypeSpec> {
    return model.dtoVariants.mapNotNull { variant ->
        val fields = model.fieldsFor(variant)
        if (fields.isNotEmpty() || model.properties.any { it is FlattenedProperty && variant in it.dtoVariants }) {
            buildDataTransferObjectClass(model, model.properties, variant, modelsByBaseName, collector)
        } else null
    }
}