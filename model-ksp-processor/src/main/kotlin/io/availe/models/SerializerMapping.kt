package io.availe.models

import kotlinx.serialization.Serializable

@Serializable
internal data class SerializerMapping(
    val typeFqn: String,
    val serializerFqn: String,
    val isSerializerObject: Boolean
)