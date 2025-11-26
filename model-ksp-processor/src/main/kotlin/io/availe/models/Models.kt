package io.availe.models

import kotlinx.serialization.Serializable

@Serializable
internal data class AnnotationConfigModel(
    val annotation: AnnotationModel,
    val variants: Set<DtoVariant>
)

@Serializable
internal data class Model(
    val name: String,
    val packageName: String,
    val properties: List<Property>,
    val dtoVariants: Set<DtoVariant>,
    val annotationConfigs: List<AnnotationConfigModel> = emptyList(),
    val annotations: List<AnnotationModel> = emptyList(),
    val optInMarkers: List<String> = emptyList(),
    val isVersionOf: String? = null,
    val schemaVersion: Int? = null,
    val visibility: DtoVisibility = DtoVisibility.PUBLIC,
    val supertypes: List<SupertypeInfo> = emptyList(),
    val typeSerializers: Map<String, SerializerMapping> = emptyMap()
) {
    init {
        require(properties.isNotEmpty()) {
            "Model validation failed for '$name': Model interfaces cannot be empty and must contain at least one property."
        }
        val invalidProperties = properties.filter {
            !this.dtoVariants.containsAll(it.dtoVariants)
        }
        require(invalidProperties.isEmpty()) {
            val count = invalidProperties.size
            val pluralS = if (count == 1) "" else "s"
            val noun = if (count == 1) "property" else "properties"
            val verb = if (count == 1) "is" else "are"
            val propertiesReport = invalidProperties.joinToString("\n") {
                " - Property: '${it.name}' (has variants '${it.dtoVariants}')"
            }
            """
            Invalid property variant$pluralS found in model '$name':
            The model's variants are '${this.dtoVariants}', which does not fully contain the property's variants.
            The following $noun $verb invalid:
            $propertiesReport
            """.trimIndent()
        }
    }
}