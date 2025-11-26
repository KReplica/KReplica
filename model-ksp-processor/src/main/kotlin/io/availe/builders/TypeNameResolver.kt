package io.availe.builders

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import io.availe.PATCHABLE_CLASS_NAME
import io.availe.models.*

internal fun resolveTypeNameForProperty(
    property: Property,
    dtoVariant: DtoVariant,
    model: Model,
    modelsByBaseName: Map<String, List<Model>>,
    isContainerSerializable: Boolean
): TypeName {
    val patchableClassName = ClassName("io.availe.models", PATCHABLE_CLASS_NAME)

    val finalAutoContextualEnabled = property.autoContextual == AutoContextual.ENABLED

    val baseType = if (property is ForeignProperty) {
        buildRecursiveDtoTypeName(property, dtoVariant, modelsByBaseName, isContainerSerializable)
    } else {
        buildTypeNameWithContextual(property.typeInfo, isContainerSerializable, finalAutoContextualEnabled)
    }

    if (dtoVariant == DtoVariant.PATCH) {
        return patchableClassName.parameterizedBy(baseType)
    }

    return baseType
}

private fun buildSimpleTypeName(typeInfo: TypeInfo): TypeName {
    val rawType = typeInfo.qualifiedName.asClassName()
    if (typeInfo.arguments.isEmpty()) {
        return rawType.copy(nullable = typeInfo.isNullable)
    }
    val typeArguments = typeInfo.arguments.map { buildSimpleTypeName(it) }
    return rawType.parameterizedBy(typeArguments).copy(nullable = typeInfo.isNullable)
}

internal fun buildRecursiveDtoTypeName(
    property: ForeignProperty,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>,
    isCurrentContainerSerializable: Boolean
): TypeName {
    val typeInfo = property.typeInfo

    val targetModel = modelsByBaseName[property.baseModelName]
        ?.find { it.name == property.versionName }
        ?: error("KReplica Error: Could not resolve foreign model for baseName='${property.baseModelName}' and versionName='${property.versionName}'.")

    if (typeInfo.arguments.isEmpty()) {
        val finalDtoName = if (targetModel.isVersionOf != null) {
            ClassName(targetModel.packageName, "${targetModel.isVersionOf}Schema", targetModel.name, dtoVariant.suffix)
        } else {
            ClassName(targetModel.packageName, "${targetModel.name}Schema", dtoVariant.suffix)
        }
        return finalDtoName.copy(nullable = typeInfo.isNullable)
    } else {
        val rawType = typeInfo.qualifiedName.asClassName()
        val transformedArgs = typeInfo.arguments.map { arg ->
            buildRecursiveDtoTypeName(
                arg,
                dtoVariant,
                modelsByBaseName,
                isCurrentContainerSerializable
            )
        }
        return rawType.parameterizedBy(transformedArgs).copy(nullable = typeInfo.isNullable)
    }
}

private fun buildRecursiveDtoTypeName(
    typeInfo: TypeInfo,
    dtoVariant: DtoVariant,
    modelsByBaseName: Map<String, List<Model>>,
    isCurrentContainerSerializable: Boolean
): TypeName {
    val simpleName = typeInfo.qualifiedName.substringAfterLast('.')
    val lookupKey = if (simpleName.endsWith("Schema")) simpleName.removeSuffix("Schema") else simpleName

    val targetModel = modelsByBaseName[lookupKey]?.firstOrNull()

    if (typeInfo.arguments.isEmpty()) {
        if (targetModel == null) {
            return buildTypeNameWithContextual(typeInfo, isCurrentContainerSerializable, true)
        }

        val finalDtoName = if (targetModel.isVersionOf != null) {
            ClassName(targetModel.packageName, "${targetModel.isVersionOf}Schema", targetModel.name, dtoVariant.suffix)
        } else {
            ClassName(targetModel.packageName, "${targetModel.name}Schema", dtoVariant.suffix)
        }
        return finalDtoName.copy(nullable = typeInfo.isNullable)
    } else {
        val rawType = typeInfo.qualifiedName.asClassName()
        val transformedArgs = typeInfo.arguments.map { arg ->
            buildRecursiveDtoTypeName(arg, dtoVariant, modelsByBaseName, isCurrentContainerSerializable)
        }
        return rawType.parameterizedBy(transformedArgs).copy(nullable = typeInfo.isNullable)
    }
}


internal fun buildTypeNameWithContextual(
    typeInfo: TypeInfo,
    isContainerSerializable: Boolean,
    autoContextualEnabled: Boolean
): TypeName {
    return buildTypeNameRecursive(
        typeInfo,
        parentRequiresContext = false,
        isContainerSerializable,
        autoContextualEnabled
    )
}

private fun buildTypeNameRecursive(
    typeInfo: TypeInfo,
    parentRequiresContext: Boolean,
    isContainerSerializable: Boolean,
    autoContextualEnabled: Boolean
): TypeName {
    val isIntrinsic = INTRINSIC_SERIALIZABLES.contains(typeInfo.qualifiedName)

    val needsContextNow =
        parentRequiresContext || (autoContextualEnabled && typeInfo.requiresContextual)

    val rawType = typeInfo.qualifiedName.asClassName()

    val typeArguments = typeInfo.arguments.map { arg ->
        buildTypeNameRecursive(arg, needsContextNow, isContainerSerializable, autoContextualEnabled)
    }

    val parameterizedType = if (typeArguments.isEmpty()) rawType else rawType.parameterizedBy(typeArguments)

    val shouldAnnotate = isContainerSerializable && (needsContextNow && !isIntrinsic)

    val finalType = if (shouldAnnotate) {
        val contextualAnnotation = AnnotationSpec.builder(ClassName("kotlinx.serialization", "Contextual")).build()
        parameterizedType.copy(annotations = parameterizedType.annotations + contextualAnnotation)
    } else {
        parameterizedType
    }

    return finalType.copy(nullable = typeInfo.isNullable)
}

internal fun TypeInfo.toTypeName(isContainerSerializable: Boolean, autoContextualEnabled: Boolean): TypeName {
    return buildTypeNameWithContextual(this, isContainerSerializable, autoContextualEnabled)
}