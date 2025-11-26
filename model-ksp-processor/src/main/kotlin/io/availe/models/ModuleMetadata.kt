package io.availe.models

import kotlinx.serialization.Serializable

@Serializable
internal data class ModuleMetadata(
    val models: List<Model>
)