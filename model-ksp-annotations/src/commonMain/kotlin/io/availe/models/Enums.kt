package io.availe.models

import kotlinx.serialization.Serializable

@Serializable
enum class DtoVariant(val suffix: String) {
    DATA("Data"),
    CREATE("CreateRequest"),
    PATCH("PatchRequest")
}

@Serializable
enum class AutoContextual {
    ENABLED,
    DISABLED,
    INHERIT
}