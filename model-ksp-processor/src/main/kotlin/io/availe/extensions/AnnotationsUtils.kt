package io.availe.extensions

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*
import io.availe.models.AnnotationArgument
import io.availe.models.AnnotationModel
import io.availe.models.DtoVariant

private val V_INT_REGEX = Regex("^V(\\d+)$")

internal fun fail(environment: SymbolProcessorEnvironment, message: String): Nothing {
    error(message)
}

internal fun KSAnnotation.isAnnotation(qualifiedName: String): Boolean {
    return this.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
}

internal fun KSClassDeclaration.extractAllOptInMarkers(): List<String> {
    val classMarkers = this.annotations
        .filter { it.isAnnotation(OPT_IN_ANNOTATION_NAME) }
        .flatMap { optInAnnotation ->
            (optInAnnotation.arguments.firstOrNull()?.value as? List<*>)?.mapNotNull {
                (it as? KSType)?.declaration?.qualifiedName?.asString()
            } ?: emptyList()
        }

    val propertyMarkers = this.getAllProperties()
        .flatMap { property -> property.annotations }
        .filter { annotation -> annotation.isAnnotation(OPT_IN_ANNOTATION_NAME) }
        .flatMap { optInAnnotation ->
            (optInAnnotation.arguments.firstOrNull()?.value as? List<*>)?.mapNotNull {
                (it as? KSType)?.declaration?.qualifiedName?.asString()
            } ?: emptyList()
        }

    return (classMarkers + propertyMarkers).distinct().toList()
}

internal data class VersioningInfo(val baseModelName: String, val schemaVersion: Int)

internal fun KSClassDeclaration.determineVersioningInfo(
    environment: SymbolProcessorEnvironment
): VersioningInfo? {
    val baseInterface = this.superTypes
        .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
        .firstOrNull {
            it.classKind == ClassKind.INTERFACE && !it.annotations.any { annotation ->
                annotation.isAnnotation(
                    MODEL_ANNOTATION_NAME
                )
            }
        }
        ?: return null
    val explicitVersion = this.annotations
        .firstOrNull { it.isAnnotation(REPLICATE_SCHEMA_VERSION_ANNOTATION_NAME) }
        ?.arguments
        ?.firstOrNull { it.name?.asString() == SCHEMA_VERSION_ARG }
        ?.value as? Int
    val inferredVersion = V_INT_REGEX.find(this.simpleName.asString())?.groupValues?.get(1)?.toIntOrNull()
    val version = explicitVersion ?: inferredVersion ?: fail(
        environment,
        "Versioned model '${this.simpleName.asString()}' must either be named 'V<N>' (e.g., V1) " +
                "or be annotated with @SchemaVersion(number = N)."
    )
    return VersioningInfo(baseInterface.simpleName.asString(), version)
}

private fun ksAnnotationToModel(
    annotation: KSAnnotation,
    frameworkDeclarations: Set<KSClassDeclaration>
): AnnotationModel? {
    val declaration = annotation.annotationType.resolve().declaration as? KSClassDeclaration ?: return null
    if (declaration in frameworkDeclarations) return null
    if (declaration.qualifiedName?.asString() == OPT_IN_ANNOTATION_NAME) return null

    val arguments = annotation.arguments.mapNotNull { argument ->
        val key = argument.name?.asString() ?: "value"
        val rawValue = argument.value ?: return@mapNotNull null

        val modelArgument: AnnotationArgument? = when (rawValue) {
            is String -> AnnotationArgument.StringValue(rawValue)
            is KSType -> AnnotationArgument.LiteralValue("${rawValue.declaration.qualifiedName!!.asString()}::class")
            is KSAnnotation -> ksAnnotationToModel(
                rawValue,
                frameworkDeclarations
            )?.let { AnnotationArgument.AnnotationValue(it) }

            is List<*> -> {
                val listContents = rawValue.joinToString(", ") { item ->
                    when (item) {
                        is String -> "\"${item.replace("\"", "\\\"")}\""
                        is KSType -> "${item.declaration.qualifiedName!!.asString()}::class"
                        else -> item.toString()
                    }
                }
                AnnotationArgument.LiteralValue("[$listContents]")
            }

            else -> AnnotationArgument.LiteralValue(rawValue.toString())
        }

        modelArgument?.let { key to it }
    }.toMap()

    return AnnotationModel(declaration.qualifiedName!!.asString(), arguments)
}


internal fun Sequence<KSAnnotation>.toAnnotationModels(
    frameworkDeclarations: Set<KSClassDeclaration>
): List<AnnotationModel> =
    mapNotNull { ksAnnotationToModel(it, frameworkDeclarations) }
        .toList()

internal fun KSClassDeclaration.isNonHiddenModelAnnotation(): Boolean {
    val isHidden = this.annotations.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == REPLICATE_HIDE_ANNOTATION_NAME
    }
    return this.classKind == ClassKind.INTERFACE && !isHidden
}

internal fun getFrameworkDeclarations(resolver: Resolver): Set<KSClassDeclaration> {
    return listOf(
        MODEL_ANNOTATION_NAME,
        REPLICATE_PROPERTY_ANNOTATION_NAME,
        REPLICATE_SCHEMA_VERSION_ANNOTATION_NAME,
        REPLICATE_APPLY_ANNOTATION_NAME,
        REPLICATE_HIDE_ANNOTATION_NAME
    )
        .mapNotNull { fullyQualifiedName ->
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(fullyQualifiedName))
        }
        .toSet()
}

internal fun KSClassDeclaration?.isGeneratedVariantContainer(): Boolean {
    if (this == null || Modifier.SEALED !in this.modifiers) return false
    val nestedDecls = this.declarations
        .filterIsInstance<KSClassDeclaration>()
        .map { it.simpleName.asString() }
        .toSet()
    return DtoVariant.entries.all { it.suffix in nestedDecls }
}