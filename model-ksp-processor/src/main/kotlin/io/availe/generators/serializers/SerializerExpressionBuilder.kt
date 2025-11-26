package io.availe.generators.serializers

import com.squareup.kotlinpoet.*

internal object SerializerExpressionBuilder {
    private val LIST_SERIALIZER = MemberName("kotlinx.serialization.builtins", "ListSerializer")
    private val SET_SERIALIZER = MemberName("kotlinx.serialization.builtins", "SetSerializer")
    private val MAP_SERIALIZER = MemberName("kotlinx.serialization.builtins", "MapSerializer")
    private val NULLABLE_EXTENSION = MemberName("kotlinx.serialization.builtins", "nullable")

    private const val LIST_FQN = "kotlin.collections.List"
    private const val SET_FQN = "kotlin.collections.Set"
    private const val MAP_FQN = "kotlin.collections.Map"

    fun build(typeName: TypeName): CodeBlock {
        val baseExpression = when (typeName) {
            is ParameterizedTypeName -> {
                when (typeName.rawType.canonicalName) {
                    LIST_FQN -> {
                        val elementSerializer = build(typeName.typeArguments[0])
                        CodeBlock.of("%M(%L)", LIST_SERIALIZER, elementSerializer)
                    }

                    SET_FQN -> {
                        val elementSerializer = build(typeName.typeArguments[0])
                        CodeBlock.of("%M(%L)", SET_SERIALIZER, elementSerializer)
                    }

                    MAP_FQN -> {
                        val keySerializer = build(typeName.typeArguments[0])
                        val valueSerializer = build(typeName.typeArguments[1])
                        CodeBlock.of("%M(%L, %L)", MAP_SERIALIZER, keySerializer, valueSerializer)
                    }

                    else -> {
                        val args = typeName.typeArguments.map { build(it) }
                        val argsFormat = args.joinToString(", ") { "%L" }
                        CodeBlock.of("%T.serializer($argsFormat)", typeName.rawType)
                    }
                }
            }

            is ClassName -> {
                CodeBlock.of("%T.serializer()", typeName.copy(nullable = false))
            }

            else -> error("Unsupported TypeName for serializer generation: $typeName")
        }

        return if (typeName.isNullable) {
            CodeBlock.of("%L.%M", baseExpression, NULLABLE_EXTENSION)
        } else {
            baseExpression
        }
    }
}