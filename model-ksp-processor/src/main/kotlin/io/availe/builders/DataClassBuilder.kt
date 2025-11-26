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
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>,
    collector: PatchSerializerCollector
): TypeSpec {
    val constructorBuilder = FunSpec.constructorBuilder()
    return TypeSpec.classBuilder(dtoVariant.suffix).apply {
        addModifiers(KModifier.DATA)
        addAnnotation(ClassName("kotlinx.serialization", "Serializable"))

        model.supertypes.forEach { supertypeInfo ->
            val baseClassName = supertypeInfo.fqn.asClassName()
            addSuperinterface(baseClassName)

            val variantSupertypeFqn = supertypeInfo.fqn + dtoVariant.suffix
            val variantClassName = variantSupertypeFqn.asClassName()
            addSuperinterface(variantClassName)
        }

        addSuperinterfacesFor(model, dtoVariant)
        addAnnotationsFor(model, dtoVariant)

        val activeProperties = properties.filter { dtoVariant in it.dtoVariants }
        activeProperties.forEach { property ->
            when (property) {
                is FlattenedProperty -> {
                    val targetModel = modelsByBaseName[property.foreignBaseModelName]
                        ?.find { it.name == property.foreignVersionName }
                        ?: error("Could not find model for flattened property: ${property.foreignBaseModelName}.${property.foreignVersionName}")

                    val targetProperties = targetModel.properties.filter { dtoVariant in it.dtoVariants }
                    targetProperties.forEach { targetProp ->
                        if (targetProp.name == SCHEMA_VERSION_PROPERTY_NAME) return@forEach
                        addConfiguredProperty(
                            constructorBuilder,
                            targetProp,
                            model,
                            dtoVariant,
                            modelsByBaseName,
                            collector
                        )
                    }
                }

                is ForeignProperty, is RegularProperty -> {
                    addConfiguredProperty(constructorBuilder, property, model, dtoVariant, modelsByBaseName, collector)
                }
            }
        }
        primaryConstructor(constructorBuilder.build())
    }.build()
}

private fun TypeSpec.Builder.addSuperinterfacesFor(model: Model, dtoVariant: DtoVariant) {
    if (model.isVersionOf != null) {
        val packageName = model.packageName
        val schemaName = model.isVersionOf + "Schema"
        val versionInterface = ClassName(packageName, schemaName, model.name)
        val variantKindInterface = ClassName(packageName, schemaName, "${dtoVariant.suffix}Variant")
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
    modelsByBaseName: Map<String, List<Model>>,
    collector: PatchSerializerCollector
) {
    val isContainerSerializable = model.isSerializable(dtoVariant)
    val typeName = resolveTypeNameForProperty(property, dtoVariant, model, modelsByBaseName, isContainerSerializable)

    val paramBuilder = ParameterSpec.builder(property.name, typeName).apply {
        val mapping = model.typeSerializers[property.typeInfo.qualifiedName]
        val hasExplicitMapping = mapping != null

        val annotationsToApply = property.annotations
            .filterNot { it.qualifiedName == OPT_IN_QUALIFIED_NAME }
            .filterNot { hasExplicitMapping && it.qualifiedName == "kotlinx.serialization.Contextual" }

        annotationsToApply.forEach { annotationModel ->
            addAnnotation(buildAnnotationSpec(annotationModel))
        }

        if (dtoVariant != DtoVariant.PATCH && hasExplicitMapping) {
            val serializerClassName = mapping!!.serializerFqn.asClassName()
            val annotation = AnnotationSpec.builder(ClassName("kotlinx.serialization", "Serializable"))
                .addMember("with = %T::class", serializerClassName)
                .build()
            addAnnotation(annotation)
        }

        if (property.name == SCHEMA_VERSION_PROPERTY_NAME && dtoVariant != DtoVariant.PATCH) {
            defaultValue("%L", model.schemaVersion)
        }

        if (dtoVariant == DtoVariant.PATCH) {
            val baseType = resolvePropertyBaseType(property, dtoVariant, modelsByBaseName)
            val serializerName = collector.getOrRegister(baseType, model.typeSerializers)
            addAnnotation(
                AnnotationSpec.builder(ClassName("kotlinx.serialization", "Serializable"))
                    .addMember("with = %T::class", serializerName)
                    .build()
            )

            val patchableClassName = ClassName(MODELS_PACKAGE_NAME, PATCHABLE_CLASS_NAME)
            defaultValue("%T.%L", patchableClassName, UNCHANGED_OBJECT_NAME)
        }
    }

    constructorBuilder.addParameter(paramBuilder.build())
    addProperty(PropertySpec.builder(property.name, typeName).initializer(property.name).build())
}