package io.availe.models

import kotlinx.serialization.Serializable

@Serializable
internal sealed class Property {
    abstract val name: String
    abstract val typeInfo: TypeInfo
    abstract val dtoVariants: Set<DtoVariant>
    abstract val annotations: List<AnnotationModel>
    abstract val autoContextual: AutoContextual
}

@Serializable
internal data class RegularProperty(
    override val name: String,
    override val typeInfo: TypeInfo,
    override val dtoVariants: Set<DtoVariant>,
    override val annotations: List<AnnotationModel> = emptyList(),
    override val autoContextual: AutoContextual
) : Property()

@Serializable
internal data class ForeignProperty(
    override val name: String,
    override val typeInfo: TypeInfo,
    val baseModelName: String,
    val versionName: String,
    override val dtoVariants: Set<DtoVariant>,
    override val annotations: List<AnnotationModel> = emptyList(),
    override val autoContextual: AutoContextual
) : Property()