package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.toAnnotationSpec

/**
 * Collects compile-time diagnostics and prepares KSP types for the
 * generated code. Diagnostics are buffered: the caller reports them at
 * the end of generation, or defers the spec to a later round when every
 * diagnostic was an unresolved type. Type rendering is delegated to
 * KotlinPoet, so nullability, arguments, annotations and function types
 * follow the library's handling instead of hand-written string building.
 * [generatedPackage] is the package of the file under generation, which
 * decides whether package-scoped declarations (Java package-private) are
 * accessible.
 */
internal class Checker(
    internal val resolver: Resolver,
    internal val logger: KSPLogger,
    internal val generatedPackage: String,
) {
    var valid = true
        private set

    private val pending = mutableListOf<Pair<KSNode, String>>()
    private var unresolvedCount = 0

    /**
     * The number of diagnostics collected so far. Callers capture it before
     * a step and compare afterwards, so a failure of one property does not
     * suppress the diagnostics of the next. Within one property the first
     * failure still stops its remaining checks to avoid cascading errors.
     */
    val diagnosticCount: Int get() = pending.size

    fun report(symbol: KSNode, message: String) {
        valid = false
        pending += symbol to message
    }

    /**
     * Records a diagnostic caused by a type that is not resolvable in this
     * round, typically because another symbol processor generates it later.
     */
    fun reportUnresolved(symbol: KSNode, message: String) {
        valid = false
        unresolvedCount++
        pending += symbol to message
    }

    /**
     * Returns `true` when every collected diagnostic was an unresolved type,
     * so processing can be deferred to a later KSP round.
     */
    fun onlyUnresolved(): Boolean = pending.isNotEmpty() && pending.size == unresolvedCount

    fun flush() {
        for ((symbol, message) in pending) logger.error(message, symbol)
        pending.clear()
    }

    fun message(annotation: KSAnnotation?, propertyName: String): String =
        annotation?.string("message").takeUnless { it.isNullOrEmpty() }
            ?: "Invalid value for property $propertyName."

    /**
     * Substitutes the type parameters recorded in [environment] throughout
     * [type], so types declared against generic bases are seen from the
     * perspective of the analyzed class. The substitution recurses into the
     * arguments of composite types.
     */
    fun substituteType(type: KSType, environment: Map<KSTypeParameter, KSType>): KSType {
        if (environment.isEmpty() || type.isError) return type
        (type.declaration as? KSTypeParameter)?.let { parameter ->
            val substituted = environment[parameter] ?: return type
            return if (type.isMarkedNullable) substituted.makeNullable() else substituted
        }
        if (type.arguments.isEmpty()) return type
        val arguments = type.arguments.map { argument ->
            val reference = argument.type ?: return@map argument
            val substituted = substituteType(reference.resolve(), environment)
            resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(substituted), argument.variance)
        }
        return type.replace(arguments)
    }

    /**
     * Applies the type-use annotations of [types] to this type. An alias
     * usage, its target, and a carried source can repeat the same annotation;
     * the highest occurrence count of any single source is emitted, so
     * repeated annotations of one source stay repeated while overlapping
     * sources do not duplicate them.
     */
    internal fun TypeName.annotated(
        types: List<KSType>,
        ignoreExtensionMarker: Boolean,
    ): TypeName {
        val sources = types.map { type ->
            type.annotations.mapNotNull { it.toRenderableSpec(ignoreExtensionMarker) }.toList()
        }
        val merged = mergeAnnotations(sources)
        return if (merged.isEmpty()) this else annotated(merged)
    }

    /**
     * Returns the annotation spec of this annotation when generated code
     * repeats it; compiler markers and annotations that are expressed
     * through the type syntax read as absent. Arguments are rendered by
     * kotlinpoet-ksp, so a `String` or `Char` argument that contains an
     * unpaired surrogate reaches the generated file as `?`.
     */
    internal fun KSAnnotation.toRenderableSpec(ignoreExtensionMarker: Boolean): AnnotationSpec? {
        if (ignoreExtensionMarker && isMarker(EXTENSION_FUNCTION_TYPE)) return null
        val declaration = annotationType.resolve().declaration
        if (declaration.qualifiedName == null || isIgnoredTypeAnnotation(declaration)) return null
        return toAnnotationSpec()
    }

    internal fun symbolDescription(symbol: KSNode): String = when (symbol) {
        is KSPropertyDeclaration -> "property ${symbol.simpleName.asString()}"
        is KSFunctionDeclaration -> "function ${symbol.simpleName.asString()}"
        else -> symbol.toString()
    }

}
