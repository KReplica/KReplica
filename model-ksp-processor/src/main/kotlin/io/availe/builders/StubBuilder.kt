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

internal fun generateStubs(declarations: List<KSClassDeclaration>, env: SymbolProcessorEnvironment) {
    if (declarations.isEmpty()) return

    val (internalDecls, publicDecls) = declarations.partition {
        getVisibilityFromAnnotation(it) == DtoVisibility.INTERNAL
    }

    val publicModelsByBaseName = publicDecls.groupBy {
        it.determineVersioningInfo(env)?.baseModelName ?: it.simpleName.asString()
    }

    publicModelsByBaseName.forEach { (baseName, versions) ->
        createStubFileFor(baseName, versions, env)
    }

    if (internalDecls.isNotEmpty()) {
        createInternalStubsFile(internalDecls, env)
    }
}

private fun createInternalStubsFile(internalDecls: List<KSClassDeclaration>, env: SymbolProcessorEnvironment) {
    val representativeModel = internalDecls.first()
    val packageName = representativeModel.packageName.asString()
    val allSourceFiles = internalDecls.mapNotNull { it.containingFile }.toTypedArray()

    val fileBuilder = FileSpec.builder(packageName, INTERNAL_SCHEMAS_FILE_NAME)

    val optInMarkers = internalDecls.flatMap { it.extractAllOptInMarkers() }.distinct()
    if (optInMarkers.isNotEmpty()) {
        val format = optInMarkers.joinToString(", ") { "%T::class" }
        val args = optInMarkers.map { it.asClassName() }.toTypedArray()
        fileBuilder.addAnnotation(
            AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                .addMember(format, *args)
                .build()
        )
    }

    val internalModelsByBaseName = internalDecls.groupBy {
        it.determineVersioningInfo(env)?.baseModelName ?: it.simpleName.asString()
    }

    internalModelsByBaseName.forEach { (_, versions) ->
        val schemaStub = buildSchemaStub(versions, env, KModifier.INTERNAL)
        fileBuilder.addType(schemaStub)
    }

    fileBuilder.build().writeTo(
        codeGenerator = env.codeGenerator,
        dependencies = Dependencies(true, *allSourceFiles)
    )
}

private fun getVisibilityFromAnnotation(declaration: KSClassDeclaration): DtoVisibility {
    val modelAnnotation = declaration.annotations.first { it.isAnnotation(MODEL_ANNOTATION_NAME) }
    val visibilityArgument = modelAnnotation.arguments.find { it.name?.asString() == "visibility" }
    return visibilityArgument?.let { DtoVisibility.valueOf((it.value as KSDeclaration).simpleName.asString()) }
        ?: DtoVisibility.PUBLIC
}

private fun createStubFileFor(baseName: String, versions: List<KSClassDeclaration>, env: SymbolProcessorEnvironment) {
    val representativeModel = versions.first()
    val packageName = representativeModel.packageName.asString()
    val schemaFileName = baseName + "Schema"
    val allSourceFiles = versions.mapNotNull { it.containingFile }.toTypedArray()

    val schemaInterface = buildSchemaStub(versions, env, KModifier.PUBLIC)

    FileSpec.builder(packageName, schemaFileName).apply {
        addType(schemaInterface)
        val optInMarkers = versions.flatMap { it.extractAllOptInMarkers() }.distinct()
        if (optInMarkers.isNotEmpty()) {
            val format = optInMarkers.joinToString(", ") { "%T::class" }
            val args = optInMarkers.map { it.asClassName() }.toTypedArray()
            addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .addMember(format, *args)
                    .build()
            )
        }
    }.build().writeTo(
        codeGenerator = env.codeGenerator,
        dependencies = Dependencies(true, *allSourceFiles)
    )
}

private fun buildSchemaStub(
    versions: List<KSClassDeclaration>,
    env: SymbolProcessorEnvironment,
    modifier: KModifier
): TypeSpec {
    val representativeModel = versions.first()
    val baseName = representativeModel.determineVersioningInfo(env)?.baseModelName
        ?: representativeModel.simpleName.asString()
    val packageName = representativeModel.packageName.asString()
    val schemaFileName = baseName + "Schema"
    val schemaClassName = ClassName(packageName, schemaFileName)
    val isGloballySerializable = versions.any { isModelSerializable(it) }

    return TypeSpec.interfaceBuilder(schemaClassName).apply {
        addModifiers(KModifier.SEALED, modifier)
        if (isGloballySerializable) {
            addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
        }

        if (representativeModel.determineVersioningInfo(env) != null) {
            versions.forEach { versionDecl ->
                val versionInterface = buildVersionedStub(versionDecl, schemaClassName, isGloballySerializable, env)
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
    versionDecl: KSClassDeclaration,
    schemaClassName: ClassName,
    isGloballySerializable: Boolean,
    env: SymbolProcessorEnvironment
): TypeSpec {
    versionDecl.determineVersioningInfo(env)
        ?: error("Could not determine version info for ${versionDecl.simpleName.asString()}")
    val versionClassName = schemaClassName.nestedClass(versionDecl.simpleName.asString())
    val isVersionSerializable = isModelSerializable(versionDecl)

    return TypeSpec.interfaceBuilder(versionClassName).apply {
        addModifiers(KModifier.SEALED)
        addSuperinterface(schemaClassName)
        if (isGloballySerializable || isVersionSerializable) {
            addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
        }

        val variants = getVariantsFromAnnotation(versionDecl)
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
        val annotationsArg = annotation.arguments.find { it.name?.asString() == "annotations" }?.value as? List<*>
        annotationsArg?.any {
            (it as? KSType)?.declaration?.qualifiedName?.asString() == SERIALIZABLE_ANNOTATION_FQN
        } ?: false
    }
}