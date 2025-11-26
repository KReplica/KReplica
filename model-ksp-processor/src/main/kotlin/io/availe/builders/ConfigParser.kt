package io.availe.builders

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.availe.extensions.KREPLICA_SERIALIZATION_CONFIG_INTERFACE
import io.availe.extensions.REPLICATE_CONFIG_ANNOTATION_NAME
import io.availe.extensions.REPLICATE_TYPE_SERIALIZER_ANNOTATION_NAME
import io.availe.extensions.isAnnotation

internal fun Resolver.getGlobalSerializerMappings(): Map<String, SerializerMapping> {
    val configSymbols = this.getSymbolsWithAnnotation(REPLICATE_CONFIG_ANNOTATION_NAME)
        .filterIsInstance<KSClassDeclaration>()
        .toList()

    if (configSymbols.size > 1) {
        error("Multiple classes found with @Replicate.Config. Only one global configuration is allowed per module. Found: ${configSymbols.map { it.simpleName.asString() }}")
    }

    val configDecl = configSymbols.firstOrNull() ?: return emptyMap()

    if (configDecl.classKind != ClassKind.OBJECT) {
        error("@Replicate.Config must be applied to an 'object', but '${configDecl.simpleName.asString()}' is a ${configDecl.classKind}.")
    }

    val implementsInterface = configDecl.superTypes.any {
        it.resolve().declaration.qualifiedName?.asString() == KREPLICA_SERIALIZATION_CONFIG_INTERFACE
    }

    if (!implementsInterface) {
        error("Object '${configDecl.simpleName.asString()}' annotated with @Replicate.Config must implement '$KREPLICA_SERIALIZATION_CONFIG_INTERFACE'.")
    }

    return extractSerializerMappings(configDecl)
}

internal fun extractSerializerMappings(declaration: KSAnnotated): Map<String, SerializerMapping> {
    val mappings = mutableMapOf<String, SerializerMapping>()

    declaration.annotations.forEach { annotation ->
        if (annotation.isAnnotation(REPLICATE_TYPE_SERIALIZER_ANNOTATION_NAME)) {
            parseTypeSerializerAnnotation(annotation)?.let {
                mappings[it.typeFqn] = it
            }
        }
    }

    return mappings
}

private fun parseTypeSerializerAnnotation(annotation: KSAnnotation): SerializerMapping? {
    val targetType = annotation.arguments.find { it.name?.asString() == "type" }?.value as? KSType ?: return null
    val targetFqn = targetType.declaration.qualifiedName?.asString() ?: return null

    val serializerType =
        annotation.arguments.find { it.name?.asString() == "serializer" }?.value as? KSType ?: return null
    val serializerDecl = serializerType.declaration as? KSClassDeclaration ?: return null
    val serializerFqn = serializerDecl.qualifiedName?.asString() ?: return null

    val isObject = serializerDecl.classKind == ClassKind.OBJECT

    return SerializerMapping(
        typeFqn = targetFqn,
        serializerFqn = serializerFqn,
        isSerializerObject = isObject
    )
}