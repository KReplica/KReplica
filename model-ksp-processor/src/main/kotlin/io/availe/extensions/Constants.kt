package io.availe.extensions

import io.availe.Replicate

internal val MODEL_ANNOTATION_NAME: String = Replicate.Model::class.qualifiedName!!
internal val REPLICATE_APPLY_ANNOTATION_NAME: String = Replicate.Apply::class.qualifiedName!!
internal val REPLICATE_PROPERTY_ANNOTATION_NAME: String = Replicate.Property::class.qualifiedName!!
internal val REPLICATE_SCHEMA_VERSION_ANNOTATION_NAME: String = Replicate.SchemaVersion::class.qualifiedName!!
internal val REPLICATE_HIDE_ANNOTATION_NAME: String = Replicate.Hide::class.qualifiedName!!
internal const val SERIALIZABLE_ANNOTATION_FQN: String = "kotlinx.serialization.Serializable"

internal const val SCHEMA_VERSION_ARG: String = "number"
internal const val SCHEMA_VERSION_FIELD: String = "schemaVersion"
internal const val OPT_IN_ANNOTATION_NAME: String = "kotlin.OptIn"