package io.availe.extensions

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import io.availe.models.TypeInfo

internal class ResolutionException(message: String) : Exception(message)

internal data class KSTypeInfo(
    val qualifiedName: String,
    val arguments: List<KSTypeInfo>,
    val isNullable: Boolean,
    val isEnum: Boolean,
    val isValueClass: Boolean,
    val isDataClass: Boolean,
    val annotations: List<io.availe.models.AnnotationModel>
) {
    companion object {
        private const val JVM_INLINE_ANNOTATION_FQN = "kotlin.jvm.JvmInline"

        fun from(
            ksType: KSType,
            environment: SymbolProcessorEnvironment,
            resolver: Resolver,
            frameworkDeclarations: Set<KSClassDeclaration>
        ): KSTypeInfo {
            if (ksType.isError) {
                val typeName = ksType.declaration.simpleName.asString()
                throw ResolutionException("KReplica: Cannot resolve type '$typeName'. This often happens if the type is not yet generated, is invalid, or is missing an import.")
            }

            val decl = ksType.declaration as KSClassDeclaration
            val qualified = decl.qualifiedName?.asString()
                ?: throw IllegalStateException("Failed to get qualified name for declaration '${decl.simpleName.asString()}'")

            val args = ksType.arguments.mapNotNull {
                it.type?.resolve()?.let { type -> from(type, environment, resolver, frameworkDeclarations) }
            }
            val nullable = ksType.isMarkedNullable
            val isEnum = decl.classKind == ClassKind.ENUM_CLASS
            val isData = decl.modifiers.contains(Modifier.DATA)

            val isValueByModifier = decl.modifiers.contains(Modifier.VALUE)
            val isValueByAnnotation = decl.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == JVM_INLINE_ANNOTATION_FQN
            }
            val isValue = isValueByModifier || isValueByAnnotation

            val annotations = ksType.annotations.toAnnotationModels(frameworkDeclarations)

            return KSTypeInfo(qualified, args, nullable, isEnum, isValue, isData, annotations)
        }
    }
}

internal fun KSTypeInfo.toModelTypeInfo(): TypeInfo =
    TypeInfo(
        qualifiedName = qualifiedName,
        arguments = arguments.map { it.toModelTypeInfo() },
        isNullable = isNullable,
        isEnum = isEnum,
        isValueClass = isValueClass,
        isDataClass = isDataClass,
        annotations = annotations
    )