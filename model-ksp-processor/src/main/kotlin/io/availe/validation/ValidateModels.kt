package io.availe.validation

import io.availe.models.DtoVariant
import io.availe.models.ForeignProperty
import io.availe.models.Model
import io.availe.models.Property
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("io.availe.utils.ValidateModels")

internal fun Model.fieldsFor(dtoVariant: DtoVariant): List<Property> =
    this.properties.filter { dtoVariant in it.dtoVariants }

internal fun validateModelReplications(allModels: List<Model>) {
    val modelsByBaseAndVersion = allModels.associateBy { (it.isVersionOf) to it.name }
    val validationErrors = mutableListOf<String>()
    allModels.forEach { model ->
        if (DtoVariant.CREATE in model.dtoVariants && model.fieldsFor(DtoVariant.CREATE).isEmpty()) {
            validationErrors.add(createEmptyVariantError(model, DtoVariant.CREATE))
        }
        if (DtoVariant.PATCH in model.dtoVariants && model.fieldsFor(DtoVariant.PATCH).isEmpty()) {
            validationErrors.add(createEmptyVariantError(model, DtoVariant.PATCH))
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
        logger.error(finalReport)
        error(finalReport)
    }
}

private fun createEmptyVariantError(model: Model, dtoVariant: DtoVariant): String {
    return """
    Model '${model.name}' has invalid configuration.
    It is declared to support the ${dtoVariant.name} variant, but it contains no properties for this variant. This would result in an empty '${model.name}${dtoVariant.suffix}' class.
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