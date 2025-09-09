package io.availe.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TypeInfo(
    val qualifiedName: String,
    val arguments: List<TypeInfo> = emptyList(),
    val isNullable: Boolean = false,
    val isEnum: Boolean = false,
    val isValueClass: Boolean = false,
    val isDataClass: Boolean = false,
    val requiresContextual: Boolean = false
)