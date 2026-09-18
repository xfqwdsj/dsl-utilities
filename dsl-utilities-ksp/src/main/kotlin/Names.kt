package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*

/**
 * Lowercases the first character of [name] unless the second character is
 * uppercase, so an acronym like `HTMLDsl` keeps its spelling.
 */
internal fun decapitalize(name: String): String =
    if (name.length >= 2 && name[1].isUpperCase()) name else name.replaceFirstChar { it.lowercase() }

/**
 * Returns `true` when [name] is a simple identifier that can name a
 * generated declaration. Names with separators or other characters cannot
 * be emitted as class or function names; hard keywords can, because
 * KotlinPoet emits them quoted with backticks.
 */
internal fun isSimpleIdentifier(name: String): Boolean =
    NameAllocator(preallocateKeywords = false).newName(name) == name

/**
 * The names of the generated classes and function for one DslBuilder
 * interface, derived from the interface name and overridden by the
 * [DslBuilder][top.ltfan.dslutilities.DslBuilder] annotation arguments.
 */
internal class Names(
    val resultName: String,
    val builderName: String,
    val functionName: String,
    val generateFunction: Boolean,
) {
    fun builderType(packageName: String): ClassName = ClassName(packageName, builderName)

    fun resultType(packageName: String): ClassName = ClassName(packageName, resultName)

    companion object {
        fun of(specName: String, annotation: KSAnnotation?): Names {
            val baseName = derivedBaseName(specName)
            val defaultResult = if (baseName == specName) "${specName}Result" else baseName
            val defaultBuilder = "${specName}Builder"
            val defaultFunction = "build$baseName"
            val resultName = annotation?.string("resultName").takeUnless { it.isNullOrEmpty() } ?: defaultResult
            val builderName = annotation?.string("builderName").takeUnless { it.isNullOrEmpty() } ?: defaultBuilder
            val generateFunction = annotation?.boolean("generateFunction") ?: true
            val functionName = annotation?.string("functionName").takeUnless { it.isNullOrEmpty() }
                ?: defaultFunction.takeIf { generateFunction }.orEmpty()
            return Names(resultName, builderName, functionName, generateFunction)
        }
    }
}

/**
 * The name of a DslBuilder interface without its `Dsl` suffix; the name
 * itself when it has no suffix or the suffix is the whole name. The
 * result, builder and function names derive from this base.
 */
internal fun derivedBaseName(specName: String): String =
    specName.removeSuffix("Dsl").takeIf { it.isNotEmpty() } ?: specName
