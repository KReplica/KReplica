package io.availe

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.availe.builders.buildModel
import io.availe.builders.generateStubs
import io.availe.extensions.KReplicaAnnotationContext
import io.availe.extensions.MODEL_ANNOTATION_NAME
import io.availe.extensions.getFrameworkDeclarations
import io.availe.extensions.isNonHiddenModelAnnotation
import io.availe.models.KReplicaPaths
import io.availe.models.Model
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter
import kotlin.system.exitProcess

private val jsonParser = Json { ignoreUnknownKeys = true }
private val jsonPrettyPrinter = Json { prettyPrint = true }

internal class ModelProcessor(private val env: SymbolProcessorEnvironment) : SymbolProcessor {
    private val state = ProcessingState()
    private typealias Round = ProcessingState.ProcessingRound

    private fun loadModelsFromOtherModules() {
        val metadataPaths = env.options["kreplica.metadataFiles"]?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() } ?: emptyList()

        val loadedModels = metadataPaths
            .map { path -> File(path) }
            .filter { file -> file.name == KReplicaPaths.MODELS_JSON_FILE }
            .flatMap { jsonFile ->
                if (jsonFile.exists() && jsonFile.length() > 0) {
                    try {
                        jsonParser.decodeFromString<List<Model>>(jsonFile.readText())
                    } catch (e: Exception) {
                        env.logger.error("--- KREPLICA-KSP: Failed to parse metadata file: ${jsonFile.absolutePath} ---\n${e.stackTraceToString()}")
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
        state.upstreamModels.addAll(loadedModels)
        state.initialized = true
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (!state.initialized) {
            loadModelsFromOtherModules()
        }

        return when (state.round) {
            Round.FIRST -> processStubs(resolver)
            Round.SECOND -> processModels(resolver)
        }
    }

    private fun processStubs(resolver: Resolver): List<KSAnnotated> {
        val modelSymbols = resolver
            .getSymbolsWithAnnotation(MODEL_ANNOTATION_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.isNonHiddenModelAnnotation() }
            .toList()

        if (modelSymbols.isNotEmpty()) {
            generateStubs(modelSymbols, env)
        }

        state.round = Round.SECOND
        return modelSymbols
    }

    private fun processModels(resolver: Resolver): List<KSAnnotated> {
        val modelSymbols = resolver
            .getSymbolsWithAnnotation(MODEL_ANNOTATION_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.isNonHiddenModelAnnotation() }
            .toList()

        val modelAnnotationDeclaration =
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(MODEL_ANNOTATION_NAME))
                ?: error("Could not resolve @Replicate.Model annotation declaration.")
        val annotationContext = KReplicaAnnotationContext(modelAnnotation = modelAnnotationDeclaration)

        val frameworkDecls = getFrameworkDeclarations(resolver)
        val builtModels = modelSymbols.map { decl ->
            buildModel(decl, resolver, frameworkDecls, annotationContext, env)
        }
        this.state.builtModels.addAll(builtModels)
        this.state.sourceSymbols.addAll(modelSymbols)
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
            KReplicaCodegen.execute(
                primaryModels = this.state.builtModels,
                allModels = allKnownModels,
                codeGenerator = env.codeGenerator,
                dependencies = dependencies
            )
        } catch (e: Exception) {
            env.logger.error("--- KREPLICA-KSP: Code generation failed with an exception ---\n${e.stackTraceToString()}")
            exitProcess(1)
        }

        writeModelsToFile(this.state.builtModels, this.state.sourceSymbols.toList())
    }

    private fun writeModelsToFile(models: List<Model>, sourceSymbols: List<KSClassDeclaration>) {
        val jsonText = jsonPrettyPrinter.encodeToString(models)
        val sourceFiles = sourceSymbols.mapNotNull { it.containingFile }.distinct().toTypedArray()
        val dependencies = Dependencies(true, *sourceFiles)
        val fileName = KReplicaPaths.MODELS_JSON_FILE
        val file = env.codeGenerator.createNewFile(dependencies, "", fileName, "")
        env.logger.info("--- KREPLICA-KSP: Writing ${models.size} models to models.json for downstream consumers. ---")
        OutputStreamWriter(file, "UTF-8").use { it.write(jsonText) }
    }

    private class ProcessingState {
        val builtModels = mutableListOf<Model>()
        val sourceSymbols = mutableSetOf<KSClassDeclaration>()
        var round = ProcessingRound.FIRST
        val upstreamModels = mutableListOf<Model>()
        var initialized = false

        enum class ProcessingRound {
            FIRST, SECOND
        }
    }
}