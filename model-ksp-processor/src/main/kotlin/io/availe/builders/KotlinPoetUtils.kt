package io.availe.builders

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import io.availe.models.AnnotationArgument
import io.availe.models.AnnotationModel
import java.nio.charset.StandardCharsets

internal fun String.asClassName(): ClassName {
    val cleanName = this.substringBefore('<').removeSuffix("?")
    return ClassName.bestGuess(cleanName)
}

internal fun buildAnnotationSpec(annotationModel: AnnotationModel): AnnotationSpec {
    val className = annotationModel.qualifiedName.asClassName()
    val builder = AnnotationSpec.builder(className)
    annotationModel.arguments.forEach { (argName, argVal) ->
        when (argVal) {
            is AnnotationArgument.StringValue -> builder.addMember("%L = %S", argName, argVal.value)
            is AnnotationArgument.LiteralValue -> builder.addMember("%L = %L", argName, argVal.value)
            is AnnotationArgument.AnnotationValue -> {
                val nestedAnnotationSpec = buildAnnotationSpec(argVal.value)
                builder.addMember("%L = %L", argName, nestedAnnotationSpec)
            }
        }
    }
    return builder.build()
}

internal fun overwriteFile(fileSpec: FileSpec, codeGenerator: CodeGenerator, dependencies: Dependencies) {
    val content = fileSpec.toString().toByteArray(StandardCharsets.UTF_8)
    try {
        codeGenerator.createNewFile(dependencies, fileSpec.packageName, fileSpec.name)
            .use { it.write(content) }
    } catch (e: FileAlreadyExistsException) {
        e.file.writeBytes(content)
    }
}