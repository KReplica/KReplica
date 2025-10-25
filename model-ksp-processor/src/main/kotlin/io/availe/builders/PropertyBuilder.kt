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
    modelAutoContextual: AutoContextual,
    resolver: Resolver,
    frameworkDeclarations: Set<KSClassDeclaration>,
    annotationContext: KReplicaAnnotationContext,
    environment: SymbolProcessorEnvironment
): Property {
    if (propertyDeclaration.isMutable) {
        val propertyName = propertyDeclaration.simpleName.asString()
        val interfaceName = (propertyDeclaration.parent as? KSClassDeclaration)?.simpleName?.asString() ?: "Unknown"
        fail(
            environment,
            """
            KReplica Validation Error: Property '$propertyName' in interface '$interfaceName' is declared as 'var'.
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

    val propertyAutoContextual = fieldAnnotation?.arguments
        ?.find { it.name?.asString() == "autoContextual" }
        ?.let { AutoContextual.valueOf((it.value as KSDeclaration).simpleName.asString()) }

    val finalAutoContextual = if (propertyAutoContextual != null && propertyAutoContextual != AutoContextual.INHERIT) {
        propertyAutoContextual
    } else {
        modelAutoContextual
    }

    val ksType = propertyDeclaration.type.resolve()

    when (val validationResult = ksType.validateKReplicaTypeUsage(annotationContext)) {
        is Invalid -> {
            val propertyName = propertyDeclaration.simpleName.asString()
            val parentInterfaceName =
                (propertyDeclaration.parent as? KSClassDeclaration)?.qualifiedName?.asString() ?: "Unknown Interface"
            val offendingModelName = validationResult.offendingDeclaration.simpleName.asString()
            val suggestedSchemaName = offendingModelName + "Schema"

            fail(
                environment,
                """
                KReplica Validation Error in '$parentInterfaceName':
                Property '$propertyName' has an invalid type signature: '${validationResult.fullTypeName}'.
                The type '$offendingModelName' is a KReplica model interface and cannot be used directly.

                To fix this, replace '$offendingModelName' with the generated schema: '$suggestedSchemaName'.
                """.trimIndent()
            )
        }

        is Valid -> { /* Continue processing */
        }
    }

    val typeInfo = KSTypeInfo.from(ksType, environment, resolver).toModelTypeInfo()
    val propertyAnnotations: List<AnnotationModel> =
        propertyDeclaration.annotations.toAnnotationModels(frameworkDeclarations)

    val foreignDecl = resolver.getClassDeclarationByName(
        resolver.getKSNameFromString(typeInfo.qualifiedName)
    )
    val isGeneratedForeignModel = foreignDecl.isGeneratedVariantContainer()

    return if (isGeneratedForeignModel && foreignDecl != null) {
        createForeignProperty(
            propertyDeclaration,
            typeInfo,
            foreignDecl,
            propertyVariants,
            propertyAnnotations,
            finalAutoContextual,
            environment
        )
    } else {
        RegularProperty(
            name = propertyDeclaration.simpleName.asString(),
            typeInfo = typeInfo,
            dtoVariants = propertyVariants,
            annotations = propertyAnnotations,
            autoContextual = finalAutoContextual
        )
    }
}

private fun createForeignProperty(
    propertyDeclaration: KSPropertyDeclaration,
    typeInformation: TypeInfo,
    foreignModelDeclaration: KSClassDeclaration,
    dtoVariants: Set<DtoVariant>,
    annotations: List<AnnotationModel>,
    autoContextual: AutoContextual,
    environment: SymbolProcessorEnvironment
): ForeignProperty {
    val versionName = foreignModelDeclaration.simpleName.asString()
    val baseModelDecl = foreignModelDeclaration.parentDeclaration as? KSClassDeclaration
        ?: fail(
            environment,
            "Could not determine parent declaration for generated model '${foreignModelDeclaration.qualifiedName?.asString()}'"
        )
    val baseModelName = baseModelDecl.simpleName.asString().removeSuffix(SCHEMA_SUFFIX)

    return ForeignProperty(
        name = propertyDeclaration.simpleName.asString(),
        typeInfo = typeInformation,
        baseModelName = baseModelName,
        versionName = versionName,
        dtoVariants = dtoVariants,
        annotations = annotations,
        autoContextual = autoContextual
    )
}