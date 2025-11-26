package io.availe

import io.availe.models.DtoVariant
import io.availe.models.DtoVisibility
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

object Replicate {
    @Target(AnnotationTarget.CLASS)
    annotation class Model(
        val variants: Array<DtoVariant>,
        val visibility: DtoVisibility = DtoVisibility.PUBLIC,
        val supertypes: Array<KClass<*>> = []
    )

    @Target(AnnotationTarget.PROPERTY)
    annotation class Property(
        val exclude: Array<DtoVariant> = [],
        val include: Array<DtoVariant> = []
    )

    @Repeatable
    @Target(AnnotationTarget.CLASS)
    annotation class Apply(
        val annotations: Array<KClass<out Annotation>>,
        val include: Array<DtoVariant> = [],
        val exclude: Array<DtoVariant> = []
    )

    @Target(AnnotationTarget.CLASS)
    annotation class SchemaVersion(val number: Int)

    @Target(AnnotationTarget.CLASS)
    annotation class Hide

    @Target(AnnotationTarget.PROPERTY)
    annotation class Flatten

    @Repeatable
    @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
    annotation class TypeSerializer(
        val type: KClass<*>,
        val serializer: KClass<out KSerializer<*>>
    )

    @Target(AnnotationTarget.CLASS)
    annotation class Serializers(
        val value: Array<TypeSerializer>
    )

    @Target(AnnotationTarget.CLASS)
    annotation class Config
}