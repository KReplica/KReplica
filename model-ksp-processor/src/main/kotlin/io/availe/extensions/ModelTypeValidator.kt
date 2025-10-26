package io.availe.extensions

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

internal sealed interface TypeValidationResult

internal data object Valid : TypeValidationResult

internal data class Invalid(
    val offendingDeclaration: KSClassDeclaration,
    val fullTypeName: String
) : TypeValidationResult

internal fun KSType.validateKReplicaTypeUsage(
    context: KReplicaAnnotationContext,
    property: KSPropertyDeclaration?
): TypeValidationResult {
    if (this.isError) {
        return Valid
    }

    val declaration = this.declaration
    if (declaration is KSClassDeclaration) {
        val hasModelAnnotation = declaration.annotations.any {
            it.shortName.asString() == context.modelAnnotation.simpleName.asString() &&
                    it.annotationType.resolve().declaration.qualifiedName
                        ?.asString() == context.modelAnnotation.qualifiedName?.asString()
        }
        if (hasModelAnnotation) {
            val isFlattened = property?.annotations?.any {
                it.isAnnotation(REPLICATE_FLATTEN_ANNOTATION_NAME)
            } == true

            if (isFlattened) {
                return Valid
            }
            return Invalid(declaration, this.toString())
        }
    }

    for (argument in this.arguments) {
        val argumentType = argument.type?.resolve() ?: continue
        val result = argumentType.validateKReplicaTypeUsage(context, null)
        if (result is Invalid) {
            return Invalid(result.offendingDeclaration, this.toString())
        }
    }

    return Valid
}