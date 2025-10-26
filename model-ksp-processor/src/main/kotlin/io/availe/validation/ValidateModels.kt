package io.availe.validation

import io.availe.extensions.SCHEMA_VERSION_FIELD
import io.availe.models.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("io.availe.utils.ValidateModels")

internal fun Model.fieldsFor(dtoVariant: DtoVariant): List<Property> =
    this.properties.filter { dtoVariant in it.dtoVariants && it !is FlattenedProperty }

private fun Model.getEffectiveFields(
    dtoVariant: DtoVariant,
    modelsByBaseAndVersion: Map<Pair<String?, String>, Model>
): Set<String> {
    val localFields = this.properties
        .filter { dtoVariant in it.dtoVariants }
        .filterNot { it is FlattenedProperty }
        .map { it.name }
        .toSet()

    val flattenedFields = this.properties
        .filterIsInstance<FlattenedProperty>()
        .filter { dtoVariant in it.dtoVariants }
        .flatMap { flattenedProperty ->
            val targetModelKey = (flattenedProperty.foreignBaseModelName) to (flattenedProperty.foreignVersionName)
            val targetModel = modelsByBaseAndVersion[targetModelKey]
                ?: error("Validation failed: Could not find flattened model ${flattenedProperty.foreignBaseModelName}.${flattenedProperty.foreignVersionName}")
            targetModel.getEffectiveFields(dtoVariant, modelsByBaseAndVersion)
        }

    return (localFields + flattenedFields).filterNot { it == SCHEMA_VERSION_FIELD }.toSet()
}

internal fun validateModelReplications(allModels: List<Model>) {
    val modelsByBaseAndVersion = allModels.associateBy { (it.isVersionOf) to it.name }
    val validationErrors = mutableListOf<String>()

    validateFlatteningCycles(allModels, modelsByBaseAndVersion, validationErrors)
    if (validationErrors.isNotEmpty()) {
        error(validationErrors.joinToString("\n\n"))
    }

    validateFlatteningCollisions(allModels, modelsByBaseAndVersion, validationErrors)

    allModels.forEach { model ->
        val effectiveCreateFields = model.getEffectiveFields(DtoVariant.CREATE, modelsByBaseAndVersion)
        val effectivePatchFields = model.getEffectiveFields(DtoVariant.PATCH, modelsByBaseAndVersion)

        val failingVariants = mutableListOf<DtoVariant>()
        if (DtoVariant.CREATE in model.dtoVariants && effectiveCreateFields.isEmpty()) {
            failingVariants.add(DtoVariant.CREATE)
        }
        if (DtoVariant.PATCH in model.dtoVariants && effectivePatchFields.isEmpty()) {
            failingVariants.add(DtoVariant.PATCH)
        }

        if (failingVariants.isNotEmpty()) {
            validationErrors.add(createConsolidatedEmptyVariantError(model, failingVariants))
        }

        model.properties
            .filterIsInstance<ForeignProperty>()
            .forEach { foreignProperty ->
                val targetModelKey = (foreignProperty.baseModelName) to (foreignProperty.versionName)
                val targetModel = modelsByBaseAndVersion[targetModelKey]
                    ?: error("Unknown referenced model base='${foreignProperty.baseModelName}' version='${foreignProperty.versionName}' in ${model.name}")

                if (DtoVariant.CREATE in foreignProperty.dtoVariants && !(DtoVariant.CREATE in targetModel.dtoVariants)) {
                    validationErrors.add(createDependencyError(model, foreignProperty, targetModel, DtoVariant.CREATE))
                }
                if (DtoVariant.PATCH in foreignProperty.dtoVariants && !(DtoVariant.PATCH in targetModel.dtoVariants)) {
                    validationErrors.add(createDependencyError(model, foreignProperty, targetModel, DtoVariant.PATCH))
                }
            }
    }
    if (validationErrors.isNotEmpty()) {
        val finalReport = "Model validation failed with ${validationErrors.size} error(s):\n\n" +
                validationErrors.joinToString("\n\n--------------------------------------------------\n\n")
        error(finalReport)
    }
}

private fun validateFlatteningCollisions(
    allModels: List<Model>,
    modelsByBaseAndVersion: Map<Pair<String?, String>, Model>,
    validationErrors: MutableList<String>
) {
    allModels.forEach { model ->
        val localPropertyNames = model.properties
            .filterIsInstance<RegularProperty>()
            .map { it.name }
            .toSet()

        val flattenedProperties = model.properties.filterIsInstance<FlattenedProperty>()
        if (flattenedProperties.isEmpty()) return@forEach

        val allFlattenedFieldNames = mutableMapOf<String, String>()

        flattenedProperties.forEach { flattenedProperty ->
            val targetModelKey = (flattenedProperty.foreignBaseModelName) to (flattenedProperty.foreignVersionName)
            val targetModel = modelsByBaseAndVersion[targetModelKey]!!
            val targetFields = targetModel.getEffectiveFields(DtoVariant.DATA, modelsByBaseAndVersion) +
                    targetModel.getEffectiveFields(DtoVariant.CREATE, modelsByBaseAndVersion) +
                    targetModel.getEffectiveFields(DtoVariant.PATCH, modelsByBaseAndVersion)

            targetFields.forEach { fieldName ->
                if (fieldName in localPropertyNames) {
                    validationErrors.add(
                        "Flattening collision in model '${model.name}': Property '$fieldName' " +
                                "is defined locally but is also present in the flattened property '${flattenedProperty.name}'."
                    )
                }
                if (fieldName in allFlattenedFieldNames) {
                    validationErrors.add(
                        "Flattening collision in model '${model.name}': Property '$fieldName' " +
                                "is present in multiple flattened properties ('${allFlattenedFieldNames[fieldName]}' and '${flattenedProperty.name}')."
                    )
                }
                allFlattenedFieldNames[fieldName] = flattenedProperty.name
            }
        }
    }
}

private fun validateFlatteningCycles(
    allModels: List<Model>,
    modelsByBaseAndVersion: Map<Pair<String?, String>, Model>,
    validationErrors: MutableList<String>
) {
    val flattenGraph = allModels.associate { model ->
        model to model.properties
            .filterIsInstance<FlattenedProperty>()
            .map {
                val key = (it.foreignBaseModelName) to (it.foreignVersionName)
                modelsByBaseAndVersion[key]
                    ?: error("Failed cycle check: Could not find model ${it.foreignBaseModelName}.${it.foreignVersionName}")
            }
    }

    val visited = mutableSetOf<Model>()
    val recursionStack = mutableSetOf<Model>()

    fun detectCycle(model: Model, path: List<String>) {
        visited.add(model)
        recursionStack.add(model)

        flattenGraph[model]?.forEach { neighbor ->
            val newPath = path + neighbor.name
            if (neighbor !in visited) {
                detectCycle(neighbor, newPath)
            } else if (neighbor in recursionStack) {
                val cyclePath = newPath.drop(newPath.indexOf(neighbor.name)).joinToString(" -> ")
                validationErrors.add("Flattening cycle detected: $cyclePath")
            }
        }
        recursionStack.remove(model)
    }

    allModels.forEach { if (it !in visited) detectCycle(it, listOf(it.name)) }
}


private fun createConsolidatedEmptyVariantError(model: Model, failingVariants: List<DtoVariant>): String {
    val modelName = model.name
    val promisedVariants = model.dtoVariants.joinToString(", ")
    val failingVariantNames = failingVariants.joinToString(", ")

    return """
    Validation Error in Model '$modelName':
    This model is declared with @Replicate.Model(variants = [$promisedVariants]).

    However, no effective properties were found for the following variants: [$failingVariantNames] (after considering @Replicate.Flatten).

    Why this happens:
    This usually means that all properties in '$modelName' are explicitly filtered for other variants (e.g., using @Replicate.Property(include = [DATA])), leaving no properties available for the failing variants.

    How to fix:
    1. Remove [$failingVariantNames] from the @Replicate.Model(variants = ...) list in '$modelName'.
       OR
    2. Adjust the @Replicate.Property annotations on your properties to include [$failingVariantNames].
    """.trimIndent()
}

private fun createDependencyError(
    parentModel: Model,
    violatingProperty: ForeignProperty,
    targetModel: Model,
    dtoVariant: DtoVariant
): String {
    val parentVariantClassName = "${parentModel.name}${dtoVariant.suffix}"
    val nestedVariantClassName = "${targetModel.name}${dtoVariant.suffix}"
    return """
    Cannot generate '$parentVariantClassName': required nested model '$nestedVariantClassName' cannot be generated.
    Details:
        Parent Model      : ${parentModel.name} (variants: ${parentModel.dtoVariants})
        Variant Requested : ${dtoVariant.name}
        Nested Property   : ${violatingProperty.name} (type: ${violatingProperty.baseModelName}.${violatingProperty.versionName})

    Why:
        The parent model '${parentModel.name}' is configured to generate a '${dtoVariant.name}' variant.
        This variant includes the property '${violatingProperty.name}', which refers to the model '${targetModel.name}'.
        However, '${targetModel.name}' (variants: ${targetModel.dtoVariants}) does not support the '${dtoVariant.name}' variant.

    To fix this, either add '${dtoVariant.name}' to the variants of '${targetModel.name}', or adjust the variants of the '${violatingProperty.name}' property.
    """.trimIndent()
}