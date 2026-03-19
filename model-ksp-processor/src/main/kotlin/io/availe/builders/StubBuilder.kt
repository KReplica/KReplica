package io.availe.builders

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.writeTo
import io.availe.extensions.*
import io.availe.models.DtoVariant
import io.availe.models.DtoVisibility

private const val INTERNAL_SCHEMAS_FILE_NAME = "_InternalKReplicaSchemas"

internal fun generateStubs(declarations: List<KSClassDeclaration>, environment: SymbolProcessorEnvironment) {
    if (declarations.isEmpty()) return

    val (internalDeclarations, publicDeclarations) = declarations.partition {
        getVisibilityFromAnnotation(it) == DtoVisibility.INTERNAL
    }

    val publicModelsByBaseName = publicDeclarations.groupBy {
        it.determineVersioningInfo(environment)?.baseModelName ?: it.simpleName.asString()
    }

    publicModelsByBaseName.forEach { (baseName, versions) ->
        createStubFileFor(baseName, versions, environment)
    }

    if (internalDeclarations.isNotEmpty()) {
        createInternalStubsFile(internalDeclarations, environment)
    }
}

private fun createInternalStubsFile(
    internalDeclarations: List<KSClassDeclaration>,
    environment: SymbolProcessorEnvironment
) {
    val representativeModel = internalDeclarations.first()
    val packageName = representativeModel.packageName.asString()
    val allSourceFiles = internalDeclarations.mapNotNull { it.containingFile }.toTypedArray()

    val fileBuilder = FileSpec.builder(packageName, INTERNAL_SCHEMAS_FILE_NAME)

    fileBuilder.addAnnotation(
        AnnotationSpec.builder(Suppress::class)
            .addMember("%S, %S", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
            .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            .build()
    )

    val internalModelsByBaseName = internalDeclarations.groupBy {
        it.determineVersioningInfo(environment)?.baseModelName ?: it.simpleName.asString()
    }

    internalModelsByBaseName.forEach { (_, versions) ->
        val schemaStub = buildSchemaStub(versions, environment, KModifier.INTERNAL)
        fileBuilder.addType(schemaStub)
    }

    fileBuilder.build().writeTo(
        codeGenerator = environment.codeGenerator,
        dependencies = Dependencies(true, *allSourceFiles)
    )
}

private fun getVisibilityFromAnnotation(declaration: KSClassDeclaration): DtoVisibility {
    val modelAnnotation = declaration.annotations.first { it.isAnnotation(MODEL_ANNOTATION_NAME) }
    val visibilityArgument = modelAnnotation.arguments.find { it.name?.asString() == "visibility" }
    return visibilityArgument?.let { DtoVisibility.valueOf((it.value as KSDeclaration).simpleName.asString()) }
        ?: DtoVisibility.PUBLIC
}

private fun createStubFileFor(
    baseName: String,
    versions: List<KSClassDeclaration>,
    environment: SymbolProcessorEnvironment
) {
    val representativeModel = versions.first()
    val packageName = representativeModel.packageName.asString()
    val schemaFileName = baseName + "Schema"
    val allSourceFiles = versions.mapNotNull { it.containingFile }.toTypedArray()

    val schemaInterface = buildSchemaStub(versions, environment, KModifier.PUBLIC)

    FileSpec.builder(packageName, schemaFileName).apply {
        addAnnotation(
            AnnotationSpec.builder(Suppress::class)
                .addMember("%S, %S", "OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                .build()
        )
        addType(schemaInterface)
    }.build().writeTo(
        codeGenerator = environment.codeGenerator,
        dependencies = Dependencies(true, *allSourceFiles)
    )
}

private fun buildSchemaStub(
    versions: List<KSClassDeclaration>,
    environment: SymbolProcessorEnvironment,
    modifier: KModifier
): TypeSpec {
    val representativeModel = versions.first()
    val baseName = representativeModel.determineVersioningInfo(environment)?.baseModelName
        ?: representativeModel.simpleName.asString()
    val packageName = representativeModel.packageName.asString()
    val schemaFileName = baseName + "Schema"
    val schemaClassName = ClassName(packageName, schemaFileName)
    val isGloballySerializable = versions.any { isModelSerializable(it) }

    val modelAnnotation = representativeModel.annotations.first { it.isAnnotation(MODEL_ANNOTATION_NAME) }
    val supertypesArgument = modelAnnotation.arguments
        .find { it.name?.asString() == "supertypes" }
        ?.value as? List<*>

    val supertypesClassNames = supertypesArgument
        ?.mapNotNull { it as? KSType }
        ?.filter { it.declaration.qualifiedName?.asString() != "kotlin.Nothing" }
        ?.map { it.declaration.qualifiedName!!.asString().asClassName() }
        ?: emptyList()

    return TypeSpec.interfaceBuilder(schemaClassName).apply {
        addModifiers(KModifier.SEALED, modifier)
        supertypesClassNames.forEach {
            addSuperinterface(it)
        }
        if (isGloballySerializable) {
            addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
        }

        if (representativeModel.determineVersioningInfo(environment) != null) {
            versions.forEach { versionDeclaration ->
                val versionInterface =
                    buildVersionedStub(versionDeclaration, schemaClassName, isGloballySerializable, environment)
                addType(versionInterface)
            }
        } else {
            val variants = getVariantsFromAnnotation(versions.first())
            variants.forEach { variant ->
                val variantClass = TypeSpec.classBuilder(variant.suffix).apply {
                    addSuperinterface(schemaClassName)
                    if (isGloballySerializable) {
                        addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
                    }
                }.build()
                addType(variantClass)
            }
        }
    }.build()
}

private fun buildVersionedStub(
    versionDeclaration: KSClassDeclaration,
    schemaClassName: ClassName,
    isGloballySerializable: Boolean,
    environment: SymbolProcessorEnvironment
): TypeSpec {
    versionDeclaration.determineVersioningInfo(environment)
        ?: error("Could not determine version info for ${versionDeclaration.simpleName.asString()}")
    val versionClassName = schemaClassName.nestedClass(versionDeclaration.simpleName.asString())
    val isVersionSerializable = isModelSerializable(versionDeclaration)

    return TypeSpec.interfaceBuilder(versionClassName).apply {
        addModifiers(KModifier.SEALED)
        addSuperinterface(schemaClassName)
        if (isGloballySerializable || isVersionSerializable) {
            addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
        }

        val variants = getVariantsFromAnnotation(versionDeclaration)
        variants.forEach { variant ->
            val variantClass = TypeSpec.classBuilder(variant.suffix).apply {
                addSuperinterface(versionClassName)
                if (isGloballySerializable || isVersionSerializable) {
                    addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
                }
            }.build()
            addType(variantClass)
        }
    }.build()
}

private fun getVariantsFromAnnotation(declaration: KSClassDeclaration): List<DtoVariant> {
    val modelAnnotation = declaration.annotations.first { it.isAnnotation(MODEL_ANNOTATION_NAME) }
    val variantsArgument = modelAnnotation.arguments.find { it.name?.asString() == "variants" }

    return (variantsArgument?.value as? List<*>)
        ?.mapNotNull { (it as? KSDeclaration)?.simpleName?.asString() }
        ?.map { DtoVariant.valueOf(it) }
        ?: emptyList()
}

private fun isModelSerializable(declaration: KSClassDeclaration): Boolean {
    val hasSerializableAnnotation = declaration.annotations.any { annotation ->
        annotation.isAnnotation(SERIALIZABLE_ANNOTATION_FQN)
    }
    if (hasSerializableAnnotation) return true

    return declaration.annotations.any { annotation ->
        if (!annotation.isAnnotation(REPLICATE_APPLY_ANNOTATION_NAME)) return@any false
        val annotationsArgument = annotation.arguments.find { it.name?.asString() == "annotations" }?.value as? List<*>
        annotationsArgument?.any {
            (it as? KSType)?.declaration?.qualifiedName?.asString() == SERIALIZABLE_ANNOTATION_FQN
        } ?: false
    }
}