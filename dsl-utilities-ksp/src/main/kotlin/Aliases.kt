package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.*

/**
 * Resolves type aliases in [type] down to the underlying classifier,
 * applying the alias's type arguments and preserving nullability, so
 * classification and assignment see the real declaration rather than the
 * alias.
 */
internal fun Checker.expandAliases(type: KSType): KSType {
    val chain = aliasChain(type)
    val result = if (chain.keepsAlias) type else chain.resolved
    return if (chain.nullable) result.makeNullable() else result
}

/**
 * The single walk over the alias chain of a type: the raw alias targets
 * from the outermost alias inward, the substituted expansion, whether an
 * unbound projection keeps the outermost alias, and the nullability of the
 * chain. Expansion, shape checks and annotation recovery all read this
 * chain, so its structure has one implementation.
 */
internal class AliasChain(
    val links: List<KSType>,
    val resolved: KSType,
    val keepsAlias: Boolean,
    val nullable: Boolean,
)

internal fun Checker.aliasChain(type: KSType): AliasChain {
    val links = mutableListOf<KSType>()
    var current = type
    val visited = mutableSetOf<String>()
    val unboundParameters = mutableSetOf<KSTypeParameter>()
    while (!current.isError) {
        val alias = current.declaration as? KSTypeAlias ?: break
        val aliasName = alias.qualifiedName?.asString() ?: break
        if (!visited.add(aliasName)) break
        // A star projection has no type to substitute. Track it and keep
        // expanding: a later alias can bind the parameter or drop it. If
        // the fully expanded type still mentions it, the alias is kept so
        // callers can reject the unbound parameter.
        alias.typeParameters.zip(current.arguments)
            .filter { (_, argument) -> argument.type == null }
            .mapTo(unboundParameters) { (parameter, _) -> parameter }
        val environment = alias.typeParameters.zip(current.arguments)
            .mapNotNull { (parameter, argument) ->
                argument.type?.resolve()?.let { parameter to it }
            }
            .toMap()
        val target = alias.type.resolve()
        links += target
        current = substituteType(target, environment)
    }
    val keepsAlias = unboundParameters.isNotEmpty() && containsParameter(current, unboundParameters)
    val nullable = type.isMarkedNullable || links.any { it.isMarkedNullable }
    return AliasChain(links, current, keepsAlias, nullable)
}

/**
 * Returns `true` when [type] mentions one of the type parameters in
 * [parameters].
 */
internal fun Checker.containsParameter(type: KSType, parameters: Set<KSTypeParameter>): Boolean {
    return type.declaration in parameters || type.arguments.any { argument ->
        argument.type?.resolve()?.let { containsParameter(it, parameters) } == true
    }
}

/**
 * Resolves the alias chain of [type] to the underlying type reference
 * without substituting the alias's type arguments. Substitution rebuilds
 * composite types and drops the compiler's function-type markers, so
 * shape checks read the receiver and suspend markers from this type.
 */
internal fun Checker.aliasTarget(type: KSType): KSType {
    val chain = aliasChain(type)
    val target = chain.links.lastOrNull() ?: type
    return if (chain.nullable) target.makeNullable() else target
}

/**
 * Returns whether the alias targets of [type] are nullable, so the
 * rendered alias name is nullable by itself.
 */
internal fun Checker.aliasTargetsNullable(type: KSType): Boolean =
    aliasChain(type).links.any { it.isMarkedNullable }

/**
 * Returns `true` when a link of the alias chain carries a type-use
 * annotation that expansion cannot carry over.
 */
internal fun Checker.hasUnrecoverableAliasAnnotations(type: KSType): Boolean =
    aliasChain(type).links.any { hasAnnotatedAliasLink(it) }

internal fun Checker.hasAnnotatedAliasLink(type: KSType): Boolean {
    val alias = type.declaration as? KSTypeAlias
    if (alias != null) {
        if (hasRenderableAnnotations(type)) return true
        // An argument annotation only survives expansion when the alias
        // uses the parameter it maps to; otherwise the annotation has no
        // effect and the type expands normally.
        val target = alias.type.resolve()
        val annotatedArgument = type.arguments.withIndex().any { (index, argument) ->
            val parameter = alias.typeParameters.getOrNull(index) ?: return@any false
            val argumentType = argument.type?.resolve() ?: return@any false
            hasRenderableAnnotations(argumentType) && containsParameter(target, setOf(parameter))
        }
        if (annotatedArgument) return true
    }
    return type.arguments.any { argument ->
        argument.type?.resolve()?.let { hasAnnotatedAliasLink(it) } == true
    }
}

/**
 * Returns whether [declaration] is a compiler marker or an internal
 * annotation that generated code never repeats. The diagnostic message
 * rendering, the alias recovery and the visibility check share this
 * predicate.
 */
internal fun isIgnoredTypeAnnotation(declaration: KSDeclaration): Boolean {
    val qualifiedName = declaration.qualifiedName?.asString()
    return qualifiedName in IGNORED_ANNOTATIONS || qualifiedName?.startsWith(KOTLIN_INTERNAL_PREFIX) == true
}
