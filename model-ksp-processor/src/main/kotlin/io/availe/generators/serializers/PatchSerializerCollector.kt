package io.availe.generators.serializers

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec

internal class PatchSerializerCollector(
    private val packageName: String,
    private val baseName: String
) {
    private val _generatedSerializers = mutableListOf<TypeSpec>()
    private val cache = mutableMapOf<String, ClassName>()
    private var counter = 0

    fun generatedSerializers(): List<TypeSpec> = _generatedSerializers

    fun getOrRegister(typeName: TypeName): ClassName {
        val serializerExpression = SerializerExpressionBuilder.build(typeName)
        val cacheKey = "$typeName::$serializerExpression"

        return cache.getOrPut(cacheKey) {
            val simpleName = "___${baseName}_PatchSerializer_${counter++}"
            val serializerClassName = ClassName(packageName, simpleName)

            val baseClass = ClassName("io.availe.models", "BasePatchableSerializer")
            val superType = baseClass.parameterizedBy(typeName)

            val typeSpec = TypeSpec.objectBuilder(simpleName)
                .addModifiers(KModifier.INTERNAL)
                .superclass(superType)
                .addSuperclassConstructorParameter(serializerExpression)
                .build()

            _generatedSerializers.add(typeSpec)
            serializerClassName
        }
    }
}