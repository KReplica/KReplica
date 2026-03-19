package io.availe.builders

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.availe.*
import io.availe.generators.serializers.PatchSerializerCollector
import io.availe.models.*

internal fun Model.isSerializable(dtoVariant: DtoVariant): Boolean {
    return annotationConfigs.any { config ->
        dtoVariant in config.variants && config.annotation.qualifiedName == SERIALIZABLE_QUALIFIED_NAME
    }
}

internal fun buildDataTransferObjectClass(
    model: Model,
    properties: List<Property>,
    dataTransferObjectVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>,
    collector: PatchSerializerCollector
): TypeSpec {
    val constructorBuilder = FunSpec.constructorBuilder()
    return TypeSpec.classBuilder(dataTransferObjectVariant.suffix).apply {
        addModifiers(KModifier.DATA)
        addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

        model.supertypes.forEach { supertypeInfo ->
            val baseClassName = supertypeInfo.fullyQualifiedName.asClassName()
            addSuperinterface(baseClassName)

            val variantSupertypeFullyQualifiedName = supertypeInfo.fullyQualifiedName + dataTransferObjectVariant.suffix
            val variantClassName = variantSupertypeFullyQualifiedName.asClassName()
            addSuperinterface(variantClassName)
        }

        addSuperinterfacesFor(model, dataTransferObjectVariant)
        addAnnotationsFor(model, dataTransferObjectVariant)

        val activeProperties = properties.filter { dataTransferObjectVariant in it.dtoVariants }
        activeProperties.forEach { property ->
            when (property) {
                is FlattenedProperty -> {
                    val targetModel = modelsByBaseName[property.foreignBaseModelName]
                        ?.find { it.name == property.foreignVersionName }
                        ?: error("Could not find model for flattened property: ${property.foreignBaseModelName}.${property.foreignVersionName}")

                    val targetProperties = targetModel.properties.filter { dataTransferObjectVariant in it.dtoVariants }
                    targetProperties.forEach { targetProperty ->
                        if (targetProperty.name == SCHEMA_VERSION_PROPERTY_NAME) return@forEach
                        addConfiguredProperty(
                            constructorBuilder,
                            targetProperty,
                            model,
                            dataTransferObjectVariant,
                            modelsByBaseName,
                            collector
                        )
                    }
                }

                is ForeignProperty, is RegularProperty -> {
                    addConfiguredProperty(
                        constructorBuilder,
                        property,
                        model,
                        dataTransferObjectVariant,
                        modelsByBaseName,
                        collector
                    )
                }
            }
        }
        primaryConstructor(constructorBuilder.build())
    }.build()
}

private fun TypeSpec.Builder.addSuperinterfacesFor(model: Model, dataTransferObjectVariant: DtoVariant) {
    if (model.isVersionOf != null) {
        val packageName = model.packageName
        val schemaName = model.isVersionOf + "Schema"
        val versionInterface = ClassName(packageName, schemaName, model.name)
        val variantKindInterface = ClassName(packageName, schemaName, "${dataTransferObjectVariant.suffix}Variant")
        addSuperinterface(versionInterface)
        addSuperinterface(variantKindInterface)

        val globalVariantInterfaceBase = when (dataTransferObjectVariant) {
            DtoVariant.DATA -> KReplicaDataVariant::class.asClassName()
            DtoVariant.CREATE -> KReplicaCreateVariant::class.asClassName()
            DtoVariant.PATCH -> KReplicaPatchVariant::class.asClassName()
        }
        val parameterizedGlobalVariant = globalVariantInterfaceBase.parameterizedBy(versionInterface)
        addSuperinterface(parameterizedGlobalVariant)
    }
}

private fun TypeSpec.Builder.addAnnotationsFor(model: Model, dataTransferObjectVariant: DtoVariant) {
    model.annotations.forEach { annotationModel ->
        addAnnotation(buildAnnotationSpec(annotationModel))
    }
    model.annotationConfigs.filter { dataTransferObjectVariant in it.variants }.forEach { config ->
        addAnnotation(buildAnnotationSpec(config.annotation))
    }
}

private fun TypeSpec.Builder.addConfiguredProperty(
    constructorBuilder: FunSpec.Builder,
    property: Property,
    model: Model,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>,
    collector: PatchSerializerCollector
) {
    val isContainerSerializable = model.isSerializable(dtoVariant)
    val typeName = resolveTypeNameForProperty(
        property,
        dtoVariant,
        model,
        modelsByBaseName,
        isContainerSerializable
    )

    val parameterBuilder = ParameterSpec.builder(property.name, typeName).apply {
        property.annotations.forEach { annotationModel ->
            addAnnotation(buildAnnotationSpec(annotationModel))
        }

        if (property.name == SCHEMA_VERSION_PROPERTY_NAME && dtoVariant != DtoVariant.PATCH) {
            defaultValue("%L", model.schemaVersion)
        }

        if (dtoVariant == DtoVariant.PATCH) {
            val baseType = resolvePropertyBaseType(property, dtoVariant, modelsByBaseName)
            val serializerName = collector.getOrRegister(baseType)
            addAnnotation(
                AnnotationSpec.builder(ClassName("kotlinx.serialization", "Serializable"))
                    .addMember("with = %T::class", serializerName)
                    .build()
            )

            val patchableClassName = ClassName(MODELS_PACKAGE_NAME, PATCHABLE_CLASS_NAME)
            defaultValue("%T.%L", patchableClassName, UNCHANGED_OBJECT_NAME)
        }
    }

    constructorBuilder.addParameter(parameterBuilder.build())
    addProperty(PropertySpec.builder(property.name, typeName).initializer(property.name).build())
}