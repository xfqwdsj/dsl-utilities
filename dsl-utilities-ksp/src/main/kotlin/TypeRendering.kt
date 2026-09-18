package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

/** Returns the immutable list type generated for a list result property. */
internal fun Checker.immutableListType(elementType: KSType): KSType? {
    val declaration = resolver.getClassDeclarationByName(
        resolver.getKSNameFromString(LIST.canonicalName)
    ) ?: return null
    val argument = resolver.getTypeArgument(
        resolver.createKSTypeReferenceFromKSType(expandAliases(elementType)),
        Variance.INVARIANT,
    )
    return declaration.asType(listOf(argument))
}

/**
 * Returns an overload key of the function name, its extension receiver
 * and its parameter types, after applying the declaring type's generic
 * environment.
 */
internal fun Checker.functionSignature(
    function: KSFunctionDeclaration,
    environment: Map<KSTypeParameter, KSType>,
): String {
    val parameters = function.parameters.joinToString(",") { parameter ->
        val type = substituteType(parameter.type.resolve(), environment)
        typeNameOrNull(type)?.toString() ?: type.toString()
    }
    val receiver = function.extensionReceiver?.let { reference ->
        val type = substituteType(reference.resolve(), environment)
        typeNameOrNull(type)?.toString() ?: type.toString()
    }.orEmpty()
    return "${function.simpleName.asString()}($receiver|$parameters)"
}

/**
 * Renders a type as a KotlinPoet [TypeName]. The type must already carry
 * the perspective of the analyzed class, which [substituteType] provides
 * for members inherited from generic bases.
 */
internal fun Checker.renderTypeName(symbol: KSNode, type: KSType): TypeName? = renderTypeName(symbol, type, null)

/**
 * Renders [type] and carries the type-use annotations of [annotationsFrom]
 * onto the positions it substantiates. An alias target can annotate a
 * type parameter usage, and substitution replaces that usage without its
 * annotations, so the unsubstituted type supplies them while rendering.
 */
internal fun Checker.renderTypeName(symbol: KSNode, type: KSType, annotationsFrom: KSType?): TypeName? {
    val resolved = expandAliases(type)
    if (resolved.isError) {
        reportUnresolved(symbol, "The type of ${symbolDescription(symbol)} is not resolvable yet.")
        return null
    }
    return try {
        // Substituting a declared type can drop the function-type
        // receiver marker, so the declaration-site type supplies the
        // shape whenever it describes the same classifier.
        val shape = if (annotationsFrom != null &&
            annotationsFrom.declaration.qualifiedName?.asString() ==
            resolved.declaration.qualifiedName?.asString()
        ) {
            aliasTarget(annotationsFrom)
        } else {
            aliasTarget(type)
        }
        val keptAlias = resolved.declaration is KSTypeAlias
        // Annotations on a link of the alias chain cannot be recovered by
        // expansion, so such a type is rendered by the outermost alias
        // name: the alias declaration keeps them and the type is
        // identical.
        val renderByName = keptAlias ||
                (type.declaration is KSTypeAlias && hasUnrecoverableAliasAnnotations(type))
        val aliasUsage = if (keptAlias) resolved else type
        if (!isDeclarationVisible(resolved) || !isDeclarationVisible(shape) ||
            (renderByName && !isDeclarationVisible(aliasUsage))
        ) {
            val hidden = if (renderByName) aliasUsage else resolved
            report(
                symbol,
                "The type ${typeNameOrNull(hidden) ?: hidden} of ${symbolDescription(symbol)} is not visible from generated code."
            )
            return null
        }
        // A by-name alias is rendered through its declaration, which
        // already carries the target annotations, so merging the expanded
        // type would duplicate them.
        val annotationSources = when {
            !renderByName -> listOfNotNull(type, resolved, shape, annotationsFrom)
            keptAlias -> listOfNotNull(type, resolved, annotationsFrom)
            else -> listOfNotNull(type, annotationsFrom)
        }
        if (!annotationSources.all { areTypeAnnotationsVisible(it) }) {
            report(
                symbol,
                "A type-use annotation of ${symbolDescription(symbol)} or one of its arguments is not visible from generated code."
            )
            return null
        }
        val isFunction = shape.isFunctionType || shape.isSuspendFunctionType
        // A kept alias is the result of an unbound star projection
        // anywhere in the chain, so it cannot be rendered as a function
        // type even when its own arguments carry no star.
        val hasUnboundProjection = resolved.hasUnboundProjection()
        when {
            isFunction && hasUnboundProjection -> {
                report(
                    symbol,
                    "The type of ${symbolDescription(symbol)} has a star-projected type argument, which is not supported."
                )
                null
            }

            renderByName -> {
                // An alias whose target is nullable is nullable by name
                // already; repeating the marker makes the generated file
                // warn about redundant nullability.
                val rendered = classifierTypeName(symbol, aliasUsage, aliasUsage, null) ?: return null
                val renderedAlias = if (aliasTargetsNullable(type)) {
                    rendered.copy(nullable = false)
                } else {
                    rendered
                }
                renderedAlias.annotated(annotationSources, ignoreExtensionMarker = isFunction)
            }

            else -> {
                val rendered = if (isFunction) {
                    lambdaTypeName(symbol, resolved, shape, annotationsFrom) ?: return null
                } else {
                    classifierTypeName(symbol, resolved, shape, annotationsFrom) ?: return null
                }
                rendered.annotated(annotationSources, ignoreExtensionMarker = isFunction)
            }
        }
    } catch (exception: Exception) {
        val detail = exception.message ?: exception::class.simpleName
        report(symbol, "The type of ${symbolDescription(symbol)} is unsupported: $detail")
        null
    }
}

internal fun hasRenderableAnnotations(type: KSType): Boolean =
    type.annotations.any { !isIgnoredTypeAnnotation(it.annotationType.resolve().declaration) }

/**
 * Renders a classifier type, keeping the type-use annotations of its
 * arguments which KotlinPoet's KSP bridge would otherwise drop.
 */
internal fun Checker.classifierTypeName(
    symbol: KSNode,
    type: KSType,
    shape: KSType,
    annotationsFrom: KSType?,
): TypeName? {
    val declared = type.toTypeName()
    val parameterized = declared as? ParameterizedTypeName ?: return declared
    val carried = carriedArguments(annotationsFrom, type).ifEmpty { carriedArguments(shape, type) }
    val arguments = type.arguments.mapIndexed { index, argument ->
        val reference = argument.type ?: return@mapIndexed STAR
        val argumentName = renderTypeName(symbol, reference.resolve(), carried.getOrNull(index))
            ?: return@mapIndexed STAR
        when (argument.variance) {
            Variance.COVARIANT -> WildcardTypeName.producerOf(argumentName)
            Variance.CONTRAVARIANT -> WildcardTypeName.consumerOf(argumentName)
            Variance.STAR -> STAR
            Variance.INVARIANT -> argumentName
        }
    }
    val rendered = innerClassTypeName(symbol, type.declaration as? KSClassDeclaration, parameterized, arguments)
        ?: return null
    return rendered.copy(nullable = declared.isNullable)
}

/**
 * Returns [parameterized] with [arguments] applied. An inner class of a
 * generic class renders with its enclosing type qualifiers, and KSP lists
 * a type's own arguments first and the enclosing class's arguments after
 * them, so the parameterized class name is rebuilt from the enclosing
 * declarations. Reports and returns `null` when the arguments do not line
 * up with that chain.
 */
private fun Checker.innerClassTypeName(
    symbol: KSNode,
    declaration: KSClassDeclaration?,
    parameterized: ParameterizedTypeName,
    arguments: List<TypeName>,
): TypeName? {
    if (declaration == null || arguments.size <= declaration.typeParameters.size) {
        return parameterized.copy(annotations = emptyList(), tags = emptyMap(), typeArguments = arguments)
    }
    val enclosing = generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }.toList()
    val counts = enclosing.map { it.typeParameters.size }
    if (counts.size == 1 || counts.sum() != arguments.size) {
        report(
            symbol,
            "The type of ${symbolDescription(symbol)} is unsupported: its enclosing type arguments cannot be reconstructed."
        )
        return null
    }
    var index = 0
    val slices = counts.map { count ->
        arguments.subList(index, index + count).also { index += count }
    }
    val base = slices.indexOfLast { it.isNotEmpty() }
    var current: TypeName = enclosing[base].toClassName().parameterizedBy(slices[base])
    for (level in base - 1 downTo 0) {
        current = (current as ParameterizedTypeName).nestedClass(enclosing[level].simpleName.asString(), slices[level])
    }
    return current
}

/**
 * Returns the argument types of [source] when it has the same classifier
 * and arity as [target], so annotations can be carried positionally.
 */
internal fun carriedArguments(source: KSType?, target: KSType): List<KSType?> {
    if (source == null) return emptyList()
    if (source.declaration.qualifiedName?.asString() != target.declaration.qualifiedName?.asString()) {
        return emptyList()
    }
    if (source.arguments.size != target.arguments.size) return emptyList()
    return source.arguments.map { it.type?.resolve() }
}

/**
 * Returns `true` when every declaration referenced by [type] is visible
 * from the generated file. Type aliases are transparent, so a private
 * alias can appear in a public DSL while the generated code cannot
 * reference its name; the caller passes the expanded type, so an alias
 * that expands to a visible type stays valid.
 */
internal fun Checker.isDeclarationVisible(type: KSType): Boolean {
    return isVisible(type.declaration) && type.arguments.all { argument ->
        argument.type?.resolve()?.let { isDeclarationVisible(it) } != false
    }
}

/**
 * Returns `true` when every rendered type-use annotation of [type] is
 * visible from the generated file. Annotations are copied verbatim, so a
 * private annotation class cannot be emitted even when the annotated type
 * itself is public. Only the annotations of [type] itself are checked
 * here; its parts are rendered through [renderTypeName] and checked there.
 */
internal fun Checker.areTypeAnnotationsVisible(type: KSType): Boolean {
    for (annotation in type.annotations) {
        val declaration = annotation.annotationType.resolve().declaration
        if (isIgnoredTypeAnnotation(declaration)) continue
        if (!isVisible(declaration)) return false
        if (!areAnnotationArgumentsVisible(annotation)) return false
    }
    return true
}

/**
 * Returns `true` when every declaration referenced by an annotation
 * argument is visible from the generated file. Enum constants and
 * `::class` arguments are copied verbatim.
 */
internal fun Checker.areAnnotationArgumentsVisible(annotation: KSAnnotation): Boolean =
    annotation.arguments.all { argument -> isAnnotationValueVisible(argument.value) }

internal fun Checker.isAnnotationValueVisible(value: Any?): Boolean = when (value) {
    is KSType -> isDeclarationVisible(value) && areTypeAnnotationsVisible(value)
    is KSDeclaration -> isVisible(value)
    is KSAnnotation ->
        isDeclarationVisible(value.annotationType.resolve()) && areAnnotationArgumentsVisible(value)

    is List<*> -> value.all { isAnnotationValueVisible(it) }
    is Array<*> -> value.all { isAnnotationValueVisible(it) }
    else -> true
}

/**
 * Returns `true` when [declaration] and its parents are visible at the
 * package level.
 */
internal fun isVisible(declaration: KSDeclaration): Boolean {
    var current: KSDeclaration? = declaration
    while (current != null) {
        if (!current.isAccessibleFromGeneratedCode()) return false
        current = current.parentDeclaration
    }
    return true
}

/**
 * Renders a function type as a lambda so that the suspend modifier, the
 * extension receiver and the nullability survive rendering; a parameter
 * keeps the name carried by the compiler's `ParameterName` marker when
 * substitution preserves it, and stays unnamed otherwise. [shape] is
 * the alias-resolved type that carries the receiver and suspend markers
 * dropped by substitution.
 */
internal fun Checker.lambdaTypeName(symbol: KSNode, type: KSType, shape: KSType, annotationsFrom: KSType?): TypeName? {
    val arguments = type.arguments
    val carried = carriedArguments(annotationsFrom, type).ifEmpty { carriedArguments(shape, type) }
    val isExtension = shape.annotations.any { it.isMarker(EXTENSION_FUNCTION_TYPE) }
    val receiver = if (isExtension) {
        arguments.firstOrNull()?.type?.resolve()?.let { renderTypeName(symbol, it, carried.getOrNull(0)) }
    } else {
        null
    }
    if (receiver is LambdaTypeName && !receiver.isAnnotated && !receiver.isNullable) {
        // KotlinPoet only parenthesizes an annotated or nullable
        // lambda receiver.
        report(
            symbol,
            "The type of ${symbolDescription(symbol)} is unsupported: a function type whose receiver is a function type cannot be rendered."
        )
        return null
    }
    val valueStart = if (isExtension) 1 else 0
    val valueArguments = if (isExtension) arguments.drop(1) else arguments
    val parameters = valueArguments.dropLast(1).mapIndexed { index, argument ->
        val argumentType = argument.type?.resolve()
        val typeName = argumentType?.let { renderTypeName(symbol, it, carried.getOrNull(valueStart + index)) }
            ?: ANY.copy(nullable = true)
        val name = argumentType?.annotations
            ?.firstOrNull { it.isMarker(PARAMETER_NAME) }
            ?.arguments
            ?.firstOrNull()
            ?.value as? String
        if (name == null) ParameterSpec.unnamed(typeName) else ParameterSpec.builder(name, typeName).build()
    }
    val returnType = valueArguments.lastOrNull()?.type?.resolve()
        ?.let { renderTypeName(symbol, it, carried.getOrNull(arguments.lastIndex)) }
        ?: UNIT
    return LambdaTypeName.get(receiver = receiver, parameters = parameters, returnType = returnType)
        .copy(nullable = type.isMarkedNullable, suspending = shape.isSuspendFunctionType)
}

/**
 * Renders a type for a diagnostic message without reporting failures; the
 * message accompanies an already-reported problem.
 */
internal fun Checker.typeNameOrNull(type: KSType): TypeName? = try {
    val resolved = expandAliases(type)
    if (resolved.isError) null else resolved.toTypeName()
} catch (_: Exception) {
    null
}

/**
 * Returns a key for the erasure of [type], used to detect generated
 * overloads that clash once type arguments and nullability are dropped.
 * A type without a qualified name falls back to its rendered form, since
 * there is no declaration name to compare.
 */
internal fun Checker.erasureKey(type: KSType): String =
    expandAliases(type).makeNotNullable().declaration.qualifiedName?.asString()
        ?: type.toString().substringBefore('<').removeSuffix("?")

internal fun Checker.ownerIsSubtypeOf(
    candidate: KSDeclaration,
    other: KSDeclaration,
    symbol: KSNode,
): Boolean {
    val candidateOwner = candidate.parentDeclaration as? KSClassDeclaration ?: return false
    val otherOwner = other.parentDeclaration as? KSClassDeclaration ?: return false
    val otherName = otherOwner.qualifiedName?.asString() ?: return false
    return candidateOwner.qualifiedName?.asString() != otherName &&
            findSuperTypeArguments(candidateOwner, symbol, otherName) != null
}

internal fun Checker.accepts(expected: KSType, provided: KSType, symbol: KSNode): Boolean {
    val resolvedExpected = expandAliases(expected)
    val resolvedProvided = expandAliases(provided)
    if (resolvedExpected.isAssignableFrom(resolvedProvided)) return true
    if (resolvedExpected.arguments.isNotEmpty() || resolvedProvided.arguments.isNotEmpty()) return false
    val expectedName = resolvedExpected.declaration.qualifiedName?.asString() ?: return false
    val providedDeclaration = resolvedProvided.declaration as? KSClassDeclaration ?: return false
    return findSuperTypeArguments(providedDeclaration, symbol, expectedName) != null
}

/**
 * Returns `true` when both types denote the same Kotlin type, which is
 * what makes two declarations conflicting overloads. Alias usage does not
 * affect identity and neither do type-use annotations.
 */
internal fun Checker.sameType(first: KSType?, second: KSType?): Boolean {
    if (first == null || second == null) return first == null && second == null
    val resolvedFirst = expandAliases(first)
    val resolvedSecond = expandAliases(second)
    return !resolvedFirst.isError &&
            !resolvedSecond.isError &&
            resolvedFirst.isAssignableFrom(resolvedSecond) &&
            resolvedSecond.isAssignableFrom(resolvedFirst)
}
