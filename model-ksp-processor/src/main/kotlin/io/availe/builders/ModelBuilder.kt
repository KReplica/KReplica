package io.availe.builders

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.availe.extensions.*
import io.availe.models.*

private fun parseApplyAnnotations(
    declaration: KSClassDeclaration,
    masterDtoVariants: Set<DtoVariant>,
    environment: SymbolProcessorEnvironment
): List<AnnotationConfigModel> {
    val applyAnnotations = declaration.annotations.filter {
        it.isAnnotation(REPLICATE_APPLY_ANNOTATION_NAME)
    }

    return applyAnnotations.flatMap { annotation ->
        val includeArgValue = annotation.arguments.find { it.name?.asString() == "include" }?.value
        val includeArg = (includeArgValue as? List<*>)
            ?.map { DtoVariant.valueOf((it as KSDeclaration).simpleName.asString()) }
            ?.toSet()
            ?: emptySet()

        val excludeArgValue = annotation.arguments.find { it.name?.asString() == "exclude" }?.value
        val excludeArg = (excludeArgValue as? List<*>)
            ?.map { DtoVariant.valueOf((it as KSDeclaration).simpleName.asString()) }
            ?.toSet()
            ?: emptySet()

        val annotationsToApplyValue = annotation.arguments.find { it.name?.asString() == "annotations" }?.value
        val annotationsToApply = (annotationsToApplyValue as? List<*>) ?: emptyList<Any?>()

        val allTargetedVariants = includeArg + excludeArg
        val unknownVariants = allTargetedVariants - masterDtoVariants
        if (unknownVariants.isNotEmpty()) {
            fail(
                environment,
                "KReplica Validation Error in '${declaration.simpleName.asString()}': " +
                        "@ApplyAnnotations targets unknown variants: ${unknownVariants.joinToString()}. " +
                        "Allowed variants are: [${masterDtoVariants.joinToString()}]."
            )
        }

        val initialSet = includeArg.ifEmpty { masterDtoVariants }
        val finalVariants = initialSet - excludeArg

        annotationsToApply.map { annotationToApplyType ->
            val annotationFqName = (annotationToApplyType as KSType).declaration.qualifiedName!!.asString()
            AnnotationConfigModel(
                annotation = AnnotationModel(qualifiedName = annotationFqName),
                variants = finalVariants
            )
        }
    }.toList()
}

internal fun buildModel(
    declaration: KSClassDeclaration,
    resolver: Resolver,
    frameworkDeclarations: Set<KSClassDeclaration>,
    annotationContext: KReplicaAnnotationContext,
    environment: SymbolProcessorEnvironment
): Model {
    val modelAnnotation = declaration.annotations.first { it.isAnnotation(MODEL_ANNOTATION_NAME) }

    val modelVariantsArgument = modelAnnotation.arguments.find { it.name?.asString() == "variants" }
    val modelDtoVariants = if (modelVariantsArgument == null) {
        fail(
            environment,
            "KReplica Error: The 'variants' argument is mandatory on @Replicate.Model for model '${declaration.simpleName.asString()}'. " +
                    "Please explicitly specify which variants to generate, e.g., @Replicate.Model(variants = [Variant.BASE])."
        )
    } else {
        (modelVariantsArgument.value as List<*>).map {
            DtoVariant.valueOf((it as KSDeclaration).simpleName.asString())
        }.toSet()
    }

    val modelVisibility = modelAnnotation.arguments
        .find { it.name?.asString() == "visibility" }
        ?.let { DtoVisibility.valueOf((it.value as KSDeclaration).simpleName.asString()) }
        ?: DtoVisibility.PUBLIC

    val supertypesArgument = modelAnnotation.arguments
        .find { it.name?.asString() == "supertypes" }
        ?.value as? List<*>

    val supertypeInfos = supertypesArgument
        ?.mapNotNull { it as? KSType }
        ?.filter { it.declaration.qualifiedName?.asString() != "kotlin.Nothing" }
        ?.map { ksType ->
            val decl = ksType.declaration as KSClassDeclaration
            val fqn = decl.qualifiedName!!.asString()
            val isSerializable = decl.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == SERIALIZABLE_ANNOTATION_FQN
            }
            SupertypeInfo(fqn = fqn, isSerializable = isSerializable)
        }
        ?: emptyList()

    val annotationConfigs = parseApplyAnnotations(declaration, modelDtoVariants, environment)
    val modelAnnotations = declaration.annotations.toAnnotationModels(frameworkDeclarations)

    val versioningInfo = declaration.determineVersioningInfo(environment)
    val properties = declaration.getAllProperties().map { property ->
        processProperty(
            property,
            modelDtoVariants,
            resolver,
            frameworkDeclarations,
            annotationContext,
            environment
        )
    }.toMutableList()

    if (versioningInfo != null && properties.none { it.name == SCHEMA_VERSION_FIELD }) {
        val schemaVersionProperty = RegularProperty(
            name = SCHEMA_VERSION_FIELD,
            typeInfo = TypeInfo("kotlin.Int", isNullable = false),
            dtoVariants = modelDtoVariants,
            annotations = emptyList()
        )
        properties.add(schemaVersionProperty)
    }

    val modelOptInMarkers = declaration.extractAllOptInMarkers()

    val flattenedMarkers = properties.filterIsInstance<FlattenedProperty>()
        .mapNotNull {
            resolver.getClassDeclarationByName(
                resolver.getKSNameFromString(it.typeInfo.qualifiedName)
            )
        }
        .flatMap { it.extractAllOptInMarkers() }

    val allOptInMarkers = (modelOptInMarkers + flattenedMarkers).distinct()

    return Model(
        name = declaration.simpleName.asString(),
        packageName = declaration.packageName.asString(),
        properties = properties,
        dtoVariants = modelDtoVariants,
        annotationConfigs = annotationConfigs,
        annotations = modelAnnotations,
        optInMarkers = allOptInMarkers,
        isVersionOf = versioningInfo?.baseModelName,
        schemaVersion = versioningInfo?.schemaVersion,
        visibility = modelVisibility,
        supertypes = supertypeInfos
    )
}