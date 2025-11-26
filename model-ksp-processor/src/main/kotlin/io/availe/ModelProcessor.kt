package io.availe

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.availe.builders.buildModel
import io.availe.builders.generateStubs
import io.availe.builders.getGlobalSerializerMappings
import io.availe.extensions.*
import io.availe.generators.generateInternalSchemasFile
import io.availe.generators.generatePublicSchemas
import io.availe.generators.generateSupertypesFile
import io.availe.models.*
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter
import kotlin.system.exitProcess

private val jsonParser = Json { ignoreUnknownKeys = true }
private val jsonPrettyPrinter = Json { prettyPrint = true }

internal class ModelProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {
    private val state = ProcessingState()

    private fun loadModelsFromOtherModules() {
        val metadataPaths = env.options["kreplica.metadataFiles"]?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() } ?: emptyList()

        val loadedMetadata = metadataPaths
            .map { path -> File(path) }
            .filter { file -> file.name == KReplicaPaths.MODELS_JSON_FILE }
            .mapNotNull { jsonFile ->
                if (jsonFile.exists() && jsonFile.length() > 0) {
                    try {
                        jsonParser.decodeFromString<ModuleMetadata>(jsonFile.readText())
                    } catch (e: Exception) {
                        env.logger.error("--- KREPLICA-KSP: Failed to parse metadata file: ${jsonFile.absolutePath} ---\n${e.stackTraceToString()}")
                        null
                    }
                } else {
                    null
                }
            }

        state.upstreamModels.addAll(loadedMetadata.flatMap { it.models })
        loadedMetadata.forEach { metadata ->
            state.upstreamSerializers.putAll(metadata.exportedSerializers)
        }
        state.initialized = true
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!state.initialized) {
            loadModelsFromOtherModules()
        }

        return when (state.round) {
            ProcessingState.ProcessingRound.FIRST -> processStubs(resolver)
            ProcessingState.ProcessingRound.SECOND -> processModels(resolver)
        }
    }

    private fun processStubs(resolver: Resolver): List<KSAnnotated> {
        val modelSymbols = resolver
            .getSymbolsWithAnnotation(MODEL_ANNOTATION_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.isNonHiddenModelAnnotation() }
            .toList()

        val configSymbols = resolver
            .getSymbolsWithAnnotation(REPLICATE_CONFIG_ANNOTATION_NAME)
            .toList()

        if (modelSymbols.isNotEmpty()) {
            generateStubs(modelSymbols, env)
        }

        state.round = ProcessingState.ProcessingRound.SECOND
        return modelSymbols + configSymbols
    }

    private fun processModels(resolver: Resolver): List<KSAnnotated> {
        val modelSymbols = resolver
            .getSymbolsWithAnnotation(MODEL_ANNOTATION_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.isNonHiddenModelAnnotation() }
            .toList()

        val globalSerializers = resolver.getGlobalSerializerMappings()

        val modelAnnotationDeclaration =
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(MODEL_ANNOTATION_NAME))
                ?: error("Could not resolve @Replicate.Model annotation declaration.")
        val annotationContext = KReplicaAnnotationContext(modelAnnotation = modelAnnotationDeclaration)

        val frameworkDecls = getFrameworkDeclarations(resolver)
        val builtModels = modelSymbols.map { decl ->
            buildModel(
                decl,
                resolver,
                frameworkDecls,
                annotationContext,
                env,
                state.upstreamSerializers,
                globalSerializers
            )
        }
        this.state.builtModels.addAll(builtModels)
        this.state.sourceSymbols.addAll(modelSymbols)
        this.state.globalSerializers = globalSerializers
        return emptyList()
    }

    override fun finish() {
        if (state.builtModels.isEmpty()) {
            return
        }

        try {
            val allKnownModels = (this.state.upstreamModels + this.state.builtModels).distinctBy {
                "${it.packageName}:${it.isVersionOf}:${it.name}"
            }
            val dependencies = Dependencies(true, *state.sourceSymbols.mapNotNull { it.containingFile }.toTypedArray())

            generateSupertypesFile(
                models = allKnownModels,
                codeGenerator = env.codeGenerator,
                dependencies = dependencies
            )

            KReplicaCodegen.validate(allKnownModels)

            val (internalModels, publicModels) = this.state.builtModels.partition { it.visibility == DtoVisibility.INTERNAL }

            generatePublicSchemas(
                primaryModels = publicModels,
                allModels = allKnownModels,
                codeGenerator = env.codeGenerator,
                dependencies = dependencies
            )

            if (internalModels.isNotEmpty()) {
                generateInternalSchemasFile(
                    primaryModels = internalModels,
                    allModels = allKnownModels,
                    codeGenerator = env.codeGenerator,
                    dependencies = dependencies
                )
            }
        } catch (e: Exception) {
            env.logger.error("--- KREPLICA-KSP: Code generation failed with an exception ---\n${e.stackTraceToString()}")
            exitProcess(1)
        }

        writeModelsToFile(
            this.state.builtModels,
            this.state.sourceSymbols.toList(),
            this.state.globalSerializers
        )
    }

    private fun writeModelsToFile(
        models: List<Model>,
        sourceSymbols: List<KSClassDeclaration>,
        exportedSerializers: Map<String, SerializerMapping>
    ) {
        val metadata = ModuleMetadata(
            models = models,
            exportedSerializers = exportedSerializers
        )
        val jsonText = jsonPrettyPrinter.encodeToString(metadata)
        val sourceFiles = sourceSymbols.mapNotNull { it.containingFile }.distinct().toTypedArray()
        val dependencies = Dependencies(true, *sourceFiles)
        val fileName = KReplicaPaths.MODELS_JSON_FILE
        val file = env.codeGenerator.createNewFile(dependencies, "", fileName, "")
        OutputStreamWriter(file, "UTF-8").use { it.write(jsonText) }
    }

    private class ProcessingState {
        val builtModels = mutableListOf<Model>()
        val sourceSymbols = mutableSetOf<KSClassDeclaration>()
        var round = ProcessingRound.FIRST
        val upstreamModels = mutableListOf<Model>()
        val upstreamSerializers = mutableMapOf<String, SerializerMapping>()
        var globalSerializers = mapOf<String, SerializerMapping>()
        var initialized = false

        enum class ProcessingRound {
            FIRST, SECOND
        }
    }
}