package io.availe.builders

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import io.availe.SCHEMA_SUFFIX
import io.availe.extensions.*
import io.availe.models.*

internal fun processProperty(
    propertyDeclaration: KSPropertyDeclaration,
    modelDtoVariants: Set<DtoVariant>,
    resolver: Resolver,
    frameworkDeclarations: Set<KSClassDeclaration>,
    annotationContext: KReplicaAnnotationContext,
    environment: SymbolProcessorEnvironment
): Property {
    val propertyName = propertyDeclaration.simpleName.asString()
    val parentInterfaceName =
        (propertyDeclaration.parent as? KSClassDeclaration)?.qualifiedName?.asString() ?: "Unknown Interface"

    if (propertyDeclaration.isMutable) {
        fail(
            environment,
            """
            KReplica Validation Error: Property '$propertyName' in interface '$parentInterfaceName' is declared as 'var'.
            Source model interfaces for KReplica must use immutable properties ('val').
            Please change '$propertyName' from 'var' to 'val'.
            """.trimIndent()
        )
    }

    val fieldAnnotation =
        propertyDeclaration.annotations.firstOrNull { it.isAnnotation(REPLICATE_PROPERTY_ANNOTATION_NAME) }

    val propertyVariants = if (fieldAnnotation == null) {
        modelDtoVariants
    } else {
        val includeArg = fieldAnnotation.arguments.find { it.name?.asString() == "include" }?.value as? List<*>
        val excludeArg = fieldAnnotation.arguments.find { it.name?.asString() == "exclude" }?.value as? List<*>

        val include =
            includeArg?.map { DtoVariant.valueOf((it as KSDeclaration).simpleName.asString()) }?.toSet() ?: emptySet()
        val exclude =
            excludeArg?.map { DtoVariant.valueOf((it as KSDeclaration).simpleName.asString()) }?.toSet() ?: emptySet()

        when {
            include.isNotEmpty() -> include
            exclude.isNotEmpty() -> modelDtoVariants - exclude
            else -> modelDtoVariants
        }
    }

    val ksType = propertyDeclaration.type.resolve()

    when (val validationResult = ksType.validateKReplicaTypeUsage(annotationContext, propertyDeclaration)) {
        is Invalid -> {
            val offendingModelName = validationResult.offendingDeclaration.simpleName.asString()
            val suggestedSchemaName = offendingModelName + "Schema"
            fail(
                environment,
                """
                KReplica Validation Error in '$parentInterfaceName':
                Property '$propertyName' has an invalid type signature: '${validationResult.fullTypeName}'.
                The type '$offendingModelName' is a KReplica model interface and cannot be used directly.

                To fix this, replace '$offendingModelName' with the generated schema: '${suggestedSchemaName}'.
                (Or, if you intend to embed its properties, annotate this property with @Replicate.Flatten and use the generated schema type)
                """.trimIndent()
            )
        }

        is Valid -> {}
    }

    val typeInfo = KSTypeInfo.from(ksType, environment, resolver).toModelTypeInfo()
    val propertyAnnotations: List<AnnotationModel> =
        propertyDeclaration.annotations.toAnnotationModels(frameworkDeclarations)

    val isFlattened = propertyDeclaration.annotations.any { it.isAnnotation(REPLICATE_FLATTEN_ANNOTATION_NAME) }

    val typeDeclaration = ksType.declaration as? KSClassDeclaration
    val isModelInterfaceType = typeDeclaration?.annotations?.any { anno ->
        anno.shortName.asString() == annotationContext.modelAnnotation.simpleName.asString() &&
                anno.annotationType.resolve().declaration.qualifiedName
                    ?.asString() == annotationContext.modelAnnotation.qualifiedName?.asString()
    } == true

    val foreignDecl = resolver.getClassDeclarationByName(
        resolver.getKSNameFromString(typeInfo.qualifiedName)
    )
    val isGeneratedForeignModel = foreignDecl.isGeneratedVariantContainer()

    return when {
        isGeneratedForeignModel && foreignDecl != null -> {
            val parent = foreignDecl.parentDeclaration as? KSClassDeclaration

            val (baseModelName, versionName) = if (parent == null) {
                val schemaName = foreignDecl.simpleName.asString()
                val modelName = schemaName.removeSuffix(SCHEMA_SUFFIX)
                modelName to modelName
            } else {
                val baseSchemaName = parent.simpleName.asString()
                val baseName = baseSchemaName.removeSuffix(SCHEMA_SUFFIX)
                baseName to foreignDecl.simpleName.asString()
            }

            if (isFlattened) {
                FlattenedProperty(
                    name = propertyName,
                    typeInfo = typeInfo,
                    foreignBaseModelName = baseModelName,
                    foreignVersionName = versionName,
                    dtoVariants = propertyVariants,
                    annotations = propertyAnnotations
                )
            } else {
                ForeignProperty(
                    name = propertyName,
                    typeInfo = typeInfo,
                    baseModelName = baseModelName,
                    versionName = versionName,
                    dtoVariants = propertyVariants,
                    annotations = propertyAnnotations
                )
            }
        }

        isModelInterfaceType -> {
            if (isFlattened) {
                fail(
                    environment,
                    "KReplica Validation Error in '$parentInterfaceName': Property '$propertyName' uses @Replicate.Flatten with a model interface type '${typeDeclaration.simpleName.asString()}'. " +
                            "Use the generated schema type ('${typeDeclaration.simpleName.asString()}Schema') with @Replicate.Flatten instead."
                )
            } else {
                fail(
                    environment,
                    "KReplica Validation Error in '$parentInterfaceName': Property '$propertyName' uses a model interface type '${typeDeclaration.simpleName.asString()}' directly. Use the generated schema type ('${typeDeclaration.simpleName.asString()}Schema')."
                )
            }
        }

        else -> {
            if (isFlattened) {
                fail(
                    environment,
                    "KReplica Validation Error in '$parentInterfaceName': @Replicate.Flatten on property '$propertyName' is invalid. It can only be used on properties referencing a generated KReplica schema (e.g., MyModelSchema.V1)."
                )
            }
            RegularProperty(
                name = propertyName, typeInfo = typeInfo, dtoVariants = propertyVariants,
                annotations = propertyAnnotations
            )
        }
    }
}