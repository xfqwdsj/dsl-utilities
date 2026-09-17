package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isDefault
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*

internal fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? =
    annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

/**
 * Merges the repeated annotations of [sources] into the specs to emit.
 * Each source contributes its own occurrence count; the highest count
 * of any single source wins and the specs keep the order of their first
 * appearance, so repeated annotations of one source stay repeated while
 * overlapping sources do not duplicate them.
 */
internal fun mergeAnnotations(sources: List<List<AnnotationSpec>>): List<AnnotationSpec> {
    val counts = mutableMapOf<String, Int>()
    val order = mutableListOf<String>()
    val specs = mutableMapOf<String, AnnotationSpec>()
    for (source in sources) {
        val localCounts = mutableMapOf<String, Int>()
        for (spec in source) {
            val key = spec.toString()
            if (specs.putIfAbsent(key, spec) == null) order += key
            localCounts[key] = (localCounts[key] ?: 0) + 1
        }
        for ((key, count) in localCounts) counts[key] = maxOf(counts[key] ?: 0, count)
    }
    return order.flatMap { key -> List(counts.getValue(key)) { specs.getValue(key) } }
}

/**
 * Returns the string argument [name] when the annotation provides it
 * explicitly; an argument that fell back to its default reads as absent.
 */
internal fun KSAnnotation.providedString(name: String): String? {
    val argument = arguments.firstOrNull { it.name?.asString() == name } ?: return null
    return if (argument.isDefault()) null else argument.value as? String
}

/** Returns whether this declaration has the qualified name of [className]. */
internal fun KSDeclaration.isClass(className: ClassName): Boolean =
    qualifiedName?.asString() == className.canonicalName

/**
 * Returns whether this type cannot be rendered as a regular type
 * reference: it is a kept alias (an unbound star projection
 * kept it) or one of its arguments is a star projection.
 */
internal fun KSType.hasUnboundProjection(): Boolean =
    declaration is KSTypeAlias || arguments.any { it.type == null }

/**
 * Returns whether this annotation is the compiler marker [className]. An
 * annotation that resolves to a declaration is matched by its qualified
 * name, so a user annotation that reuses the marker's short name is
 * not mistaken for it. A marker like `kotlin.ExtensionFunctionType`
 * is synthetic: KSP exposes it without a resolvable declaration,
 * so the short name derived from the constant is the fallback.
 */
internal fun KSAnnotation.isMarker(className: ClassName): Boolean {
    val qualifiedName = annotationType.resolve().declaration.qualifiedName?.asString()
    return if (qualifiedName != null) {
        qualifiedName == className.canonicalName
    } else {
        shortName.asString() == className.simpleName
    }
}

/** Returns whether this type is `kotlin.Unit`. */
internal fun KSType.isUnit(): Boolean = declaration.isClass(UNIT)

/**
 * Returns `true` when this declaration can be referenced from the generated
 * code of the module being compiled. An `internal` declaration of another
 * module is not accessible; KSP leaves [KSDeclaration.containingFile] `null`
 * for declarations that come from a compiled dependency.
 */
internal fun KSDeclaration.isAccessibleFromGeneratedCode(): Boolean = when (getVisibility()) {
    Visibility.PRIVATE, Visibility.PROTECTED, Visibility.LOCAL -> false
    Visibility.INTERNAL -> containingFile != null
    else -> true
}

internal fun KSAnnotation.string(name: String): String? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String

internal fun KSAnnotation.boolean(name: String): Boolean? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean

internal fun KSAnnotation.type(name: String): KSType? =
    when (val value = arguments.firstOrNull { it.name?.asString() == name }?.value) {
        is KSType -> value
        is KSClassDeclaration -> value.asType(emptyList())
        else -> null
    }

internal fun KSAnnotation.typeArray(name: String): List<KSType> =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
        ?.mapNotNull { element ->
            when (element) {
                is KSType -> element
                is KSClassDeclaration -> element.asType(emptyList())
                else -> null
            }
        }
        .orEmpty()
