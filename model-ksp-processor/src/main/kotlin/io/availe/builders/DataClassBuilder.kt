package io.availe.builders

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.availe.*
import io.availe.models.*

internal fun Model.isSerializable(dtoVariant: DtoVariant): Boolean {
    return annotationConfigs.any { config ->
        dtoVariant in config.variants && config.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME
    }
}

private fun getPatchableClassName(model: Model, dtoVariant: DtoVariant): ClassName {
    return if (model.isSerializable(dtoVariant)) {
        ClassName(MODELS_PACKAGE_NAME, SERIALIZABLE_PATCHABLE_CLASS_NAME)
    } else {
        ClassName(MODELS_PACKAGE_NAME, PATCHABLE_CLASS_NAME)
    }
}

internal fun buildDataTransferObjectClass(
    model: Model,
    properties: List<Property>,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>
): TypeSpec {
    val constructorBuilder = FunSpec.constructorBuilder()
    return TypeSpec.classBuilder(dtoVariant.suffix).apply {
        addModifiers(KModifier.DATA)
        addSuperinterfacesFor(model, dtoVariant)
        addAnnotationsFor(model, dtoVariant)
        properties.forEach { property ->
            addConfiguredProperty(constructorBuilder, property, model, dtoVariant, modelsByBaseName)
        }
        primaryConstructor(constructorBuilder.build())
    }.build()
}

private fun TypeSpec.Builder.addSuperinterfacesFor(model: Model, dtoVariant: DtoVariant) {
    if (model.isVersionOf != null) {
        val schemaName = model.isVersionOf + "Schema"
        val versionInterface = ClassName(model.packageName, schemaName, model.name)
        val variantKindInterface = ClassName(model.packageName, schemaName, "${dtoVariant.suffix}Variant")
        addSuperinterface(versionInterface)
        addSuperinterface(variantKindInterface)

        val globalVariantInterfaceBase = when (dtoVariant) {
            DtoVariant.DATA -> KReplicaDataVariant::class.asClassName()
            DtoVariant.CREATE -> KReplicaCreateVariant::class.asClassName()
            DtoVariant.PATCH -> KReplicaPatchVariant::class.asClassName()
        }
        val parameterizedGlobalVariant = globalVariantInterfaceBase.parameterizedBy(versionInterface)
        addSuperinterface(parameterizedGlobalVariant)
    }
}

private fun TypeSpec.Builder.addAnnotationsFor(model: Model, dtoVariant: DtoVariant) {
    model.annotations.forEach { annotationModel ->
        addAnnotation(buildAnnotationSpec(annotationModel))
    }
    model.annotationConfigs.filter { dtoVariant in it.variants }.forEach { config ->
        addAnnotation(buildAnnotationSpec(config.annotation))
    }
}

private fun TypeSpec.Builder.addConfiguredProperty(
    constructorBuilder: FunSpec.Builder,
    property: Property,
    model: Model,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>
) {
    val isContainerSerializable = model.isSerializable(dtoVariant)
    val typeName = resolveTypeNameForProperty(property, dtoVariant, model, modelsByBaseName, isContainerSerializable)

    val paramBuilder = ParameterSpec.builder(property.name, typeName).apply {
        val shouldFilterContextual = (dtoVariant == DtoVariant.PATCH)
        val annotationsToApply = property.annotations
            .filterNot { it.qualifiedName == OPT_IN_QUALIFIED_NAME }
            .filterNot { shouldFilterContextual && it.qualifiedName == "kotlinx.serialization.Contextual" }

        annotationsToApply.forEach { annotationModel ->
            addAnnotation(buildAnnotationSpec(annotationModel))
        }

        if (property.name == SCHEMA_VERSION_PROPERTY_NAME && dtoVariant != DtoVariant.PATCH) {
            defaultValue("%L", model.schemaVersion)
        }

        if (dtoVariant == DtoVariant.PATCH) {
            val patchableClassName = getPatchableClassName(model, dtoVariant)
            defaultValue("%T.%L", patchableClassName, UNCHANGED_OBJECT_NAME)
        }
    }

    constructorBuilder.addParameter(paramBuilder.build())
    addProperty(PropertySpec.builder(property.name, typeName).initializer(property.name).build())
}