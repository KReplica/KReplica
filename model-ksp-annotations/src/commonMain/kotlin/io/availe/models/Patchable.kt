package io.availe.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Used by generated PATCH request code to mark fields as updated or unchanged.
 * Use Set(value) to update a field, or Unchanged to leave it as-is.
 */
sealed interface Patchable<out T> {
    data class Set<out T>(val value: T) : Patchable<T>
    data object Unchanged : Patchable<Nothing>
}

abstract class BasePatchableSerializer<T>(
    private val dataSerializer: KSerializer<T>
) : KSerializer<Patchable<T>> {

    override val descriptor: SerialDescriptor = dataSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Patchable<T>) {
        if (value is Patchable.Set) {
            encoder.encodeSerializableValue(dataSerializer, value.value)
        }
    }

    override fun deserialize(decoder: Decoder): Patchable<T> {
        return Patchable.Set(decoder.decodeSerializableValue(dataSerializer))
    }
}