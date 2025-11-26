package io.availe.builders

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import io.availe.PATCHABLE_CLASS_NAME
import io.availe.models.*

internal fun resolvePropertyBaseType(
    property: Property,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>
): TypeName {
    return if (property is ForeignProperty) {
        buildRecursiveDtoTypeName(property, dtoVariant, modelsByBaseName)
    } else {
        property.typeInfo.toTypeName()
    }
}

internal fun resolveTypeNameForProperty(
    property: Property,
    dtoVariant: DtoVariant,
    model: Model,
    modelsByBaseName: Map<String, List<Model>>,
    isContainerSerializable: Boolean
): TypeName {
    val patchableClassName = ClassName("io.availe.models", PATCHABLE_CLASS_NAME)

    val baseType = resolvePropertyBaseType(property, dtoVariant, modelsByBaseName)

    if (dtoVariant == DtoVariant.PATCH) {
        return patchableClassName.parameterizedBy(baseType)
    }

    return baseType
}

private fun buildSimpleTypeName(typeInfo: TypeInfo): TypeName {
    val rawType = typeInfo.qualifiedName.asClassName()
    val finalType = if (typeInfo.arguments.isEmpty()) {
        rawType.copy(nullable = typeInfo.isNullable)
    } else {
        val typeArguments = typeInfo.arguments.map { buildSimpleTypeName(it) }
        rawType.parameterizedBy(typeArguments).copy(nullable = typeInfo.isNullable)
    }
    return finalType.applyAnnotations(typeInfo)
}

internal fun buildRecursiveDtoTypeName(
    property: ForeignProperty,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>
): TypeName {
    val typeInfo = property.typeInfo

    val targetModel = modelsByBaseName[property.baseModelName]
        ?.find { it.name == property.versionName }
        ?: error("KReplica Error: Could not resolve foreign model for baseName='${property.baseModelName}' and versionName='${property.versionName}'.")

    val finalType = if (typeInfo.arguments.isEmpty()) {
        val finalDtoName = if (targetModel.isVersionOf != null) {
            ClassName(targetModel.packageName, "${targetModel.isVersionOf}Schema", targetModel.name, dtoVariant.suffix)
        } else {
            ClassName(targetModel.packageName, "${targetModel.name}Schema", dtoVariant.suffix)
        }
        finalDtoName.copy(nullable = typeInfo.isNullable)
    } else {
        val rawType = typeInfo.qualifiedName.asClassName()
        val transformedArgs = typeInfo.arguments.map { arg ->
            buildRecursiveDtoTypeName(
                arg,
                dtoVariant,
                modelsByBaseName
            )
        }
        rawType.parameterizedBy(transformedArgs).copy(nullable = typeInfo.isNullable)
    }
    return finalType.applyAnnotations(typeInfo)
}

private fun buildRecursiveDtoTypeName(
    typeInfo: TypeInfo,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>
): TypeName {
    val simpleName = typeInfo.qualifiedName.substringAfterLast('.')
    val lookupKey = if (simpleName.endsWith("Schema")) simpleName.removeSuffix("Schema") else simpleName

    val targetModel = modelsByBaseName[lookupKey]?.firstOrNull()

    val finalType = if (typeInfo.arguments.isEmpty()) {
        if (targetModel == null) {
            typeInfo.toTypeName()
        } else {
            val finalDtoName = if (targetModel.isVersionOf != null) {
                ClassName(
                    targetModel.packageName,
                    "${targetModel.isVersionOf}Schema",
                    targetModel.name,
                    dtoVariant.suffix
                )
            } else {
                ClassName(targetModel.packageName, "${targetModel.name}Schema", dtoVariant.suffix)
            }
            finalDtoName.copy(nullable = typeInfo.isNullable)
        }
    } else {
        val rawType = typeInfo.qualifiedName.asClassName()
        val transformedArgs = typeInfo.arguments.map { arg ->
            buildRecursiveDtoTypeName(arg, dtoVariant, modelsByBaseName)
        }
        rawType.parameterizedBy(transformedArgs).copy(nullable = typeInfo.isNullable)
    }
    return finalType.applyAnnotations(typeInfo)
}


private fun buildTypeNameRecursive(
    typeInfo: TypeInfo
): TypeName {
    val rawType = typeInfo.qualifiedName.asClassName()

    val typeArguments = typeInfo.arguments.map { arg ->
        buildTypeNameRecursive(arg)
    }

    val parameterizedType = if (typeArguments.isEmpty()) rawType else rawType.parameterizedBy(typeArguments)

    return parameterizedType.copy(nullable = typeInfo.isNullable).applyAnnotations(typeInfo)
}

internal fun TypeInfo.toTypeName(): TypeName {
    return buildTypeNameRecursive(this)
}

private fun TypeName.applyAnnotations(typeInfo: TypeInfo): TypeName {
    if (typeInfo.annotations.isEmpty()) return this
    val specs = typeInfo.annotations.map { buildAnnotationSpec(it) }
    return this.copy(annotations = this.annotations + specs)
}