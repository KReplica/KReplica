package io.availe.models

import kotlinx.serialization.Serializable

/**
 * Used by generated PATCH request code to mark fields as updated or unchanged.
 * Use Set(value) to update a field, or Unchanged to leave it as-is.
 */
sealed interface Patchable<out T> {
    data class Set<out T>(val value: T) : Patchable<T>
    data object Unchanged : Patchable<Nothing>
}

/**
 * Used by generated serializable PATCH request code to mark fields as updated or unchanged.
 * Use Set(value) to update a field, or Unchanged to leave it as-is.
 */
@Serializable
sealed interface SerializablePatchable<out T> {
    @Serializable
    data class Set<out T>(val value: T) : SerializablePatchable<T>

    @Serializable
    data object Unchanged : SerializablePatchable<Nothing>
}
