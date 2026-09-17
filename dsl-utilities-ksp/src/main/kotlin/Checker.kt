package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

/**
 * Collects compile-time diagnostics and prepares KSP types for the
 * generated code. Diagnostics are buffered: the caller reports them at
 * the end of generation, or defers the spec to a later round when every
 * diagnostic was an unresolved type. Type rendering is delegated to
 * KotlinPoet, so nullability, arguments, annotations and function types
 * follow the library's handling instead of hand-written string building.
 */
internal class Checker(private val resolver: Resolver, private val logger: KSPLogger) {
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
     * Resolves type aliases in [type] down to the underlying classifier,
     * applying the alias's type arguments and preserving nullability, so
     * classification and assignment see the real declaration rather than the
     * alias.
     */
    fun expandAliases(type: KSType): KSType {
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
    private class AliasChain(
        val links: List<KSType>,
        val resolved: KSType,
        val keepsAlias: Boolean,
        val nullable: Boolean,
    )

    private fun aliasChain(type: KSType): AliasChain {
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
    private fun containsParameter(type: KSType, parameters: Set<KSTypeParameter>): Boolean {
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
    fun aliasTarget(type: KSType): KSType {
        val chain = aliasChain(type)
        val target = chain.links.lastOrNull() ?: type
        return if (chain.nullable) target.makeNullable() else target
    }

    /** Returns the immutable list type generated for a list result property. */
    fun immutableListType(elementType: KSType): KSType? {
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
     * Returns an overload key after applying the declaring type's generic
     * environment.
     */
    fun functionSignature(
        function: KSFunctionDeclaration,
        environment: Map<KSTypeParameter, KSType>,
    ): String {
        val parameters = function.parameters.joinToString(",") { parameter ->
            val type = substituteType(parameter.type.resolve(), environment)
            typeNameOrNull(type)?.toString() ?: type.toString()
        }
        return "${function.simpleName.asString()}($parameters)"
    }

    /**
     * Renders a type as a KotlinPoet [TypeName]. The type must already carry
     * the perspective of the analyzed class, which [substituteType] provides
     * for members inherited from generic bases.
     */
    fun renderTypeName(symbol: KSNode, type: KSType): TypeName? = renderTypeName(symbol, type, null)

    /**
     * Renders [type] and carries the type-use annotations of [annotationsFrom]
     * onto the positions it substantiates. An alias target can annotate a
     * type parameter usage, and substitution replaces that usage without its
     * annotations, so the unsubstituted type supplies them while rendering.
     */
    fun renderTypeName(symbol: KSNode, type: KSType, annotationsFrom: KSType?): TypeName? {
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
                    val rendered = classifierTypeName(symbol, aliasUsage, aliasUsage, null)
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
                        classifierTypeName(symbol, resolved, shape, annotationsFrom)
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

    /**
     * Returns whether the alias targets of [type] are nullable, so the
     * rendered alias name is nullable by itself.
     */
    private fun aliasTargetsNullable(type: KSType): Boolean =
        aliasChain(type).links.any { it.isMarkedNullable }

    /**
     * Returns `true` when a link of the alias chain carries a type-use
     * annotation that expansion cannot carry over.
     */
    private fun hasUnrecoverableAliasAnnotations(type: KSType): Boolean =
        aliasChain(type).links.any { hasAnnotatedAliasLink(it) }

    private fun hasAnnotatedAliasLink(type: KSType): Boolean {
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
    private fun isIgnoredTypeAnnotation(declaration: KSDeclaration): Boolean {
        val qualifiedName = declaration.qualifiedName?.asString()
        return qualifiedName in IGNORED_ANNOTATIONS || qualifiedName?.startsWith(KOTLIN_INTERNAL_PREFIX) == true
    }

    private fun hasRenderableAnnotations(type: KSType): Boolean =
        type.annotations.any { !isIgnoredTypeAnnotation(it.annotationType.resolve().declaration) }

    /**
     * Renders a classifier type, keeping the type-use annotations of its
     * arguments which KotlinPoet's KSP bridge would otherwise drop.
     */
    private fun classifierTypeName(
        symbol: KSNode,
        type: KSType,
        shape: KSType,
        annotationsFrom: KSType?,
    ): TypeName {
        val declared = type.toTypeName()
        val rawType = (declared as? ParameterizedTypeName)?.rawType ?: return declared
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
        return rawType.parameterizedBy(arguments).copy(nullable = declared.isNullable)
    }

    /**
     * Returns the argument types of [source] when it has the same classifier
     * and arity as [target], so annotations can be carried positionally.
     */
    private fun carriedArguments(source: KSType?, target: KSType): List<KSType?> {
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
    private fun isDeclarationVisible(type: KSType): Boolean {
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
    private fun areTypeAnnotationsVisible(type: KSType): Boolean {
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
    private fun areAnnotationArgumentsVisible(annotation: KSAnnotation): Boolean =
        annotation.arguments.all { argument -> isAnnotationValueVisible(argument.value) }

    private fun isAnnotationValueVisible(value: Any?): Boolean = when (value) {
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
    private fun isVisible(declaration: KSDeclaration): Boolean {
        var current: KSDeclaration? = declaration
        while (current != null) {
            when (current.getVisibility()) {
                Visibility.PRIVATE, Visibility.PROTECTED, Visibility.LOCAL -> return false
                else -> {}
            }
            current = current.parentDeclaration
        }
        return true
    }

    /**
     * Renders a function type as a lambda so that the suspend modifier, the
     * extension receiver and the nullability survive rendering; parameters
     * stay unnamed because their names live in compiler annotations that
     * generic substitution does not carry over. [shape] is the alias-resolved
     * type that carries the receiver and suspend markers dropped by
     * substitution.
     */
    private fun lambdaTypeName(symbol: KSNode, type: KSType, shape: KSType, annotationsFrom: KSType?): TypeName? {
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
     * Applies the type-use annotations of [types] to this type. An alias
     * usage, its target, and a carried source can repeat the same annotation;
     * the highest occurrence count of any single source is emitted, so
     * repeated annotations of one source stay repeated while overlapping
     * sources do not duplicate them.
     */
    private fun TypeName.annotated(
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
     * unpaired surrogate is emitted as `?`; the processor does not rebuild
     * those arguments.
     */
    private fun KSAnnotation.toRenderableSpec(ignoreExtensionMarker: Boolean): AnnotationSpec? {
        if (ignoreExtensionMarker && isMarker(EXTENSION_FUNCTION_TYPE)) return null
        val declaration = annotationType.resolve().declaration
        if (declaration.qualifiedName == null || isIgnoredTypeAnnotation(declaration)) return null
        return toAnnotationSpec()
    }

    /**
     * Renders a type for a diagnostic message without reporting failures; the
     * message accompanies an already-reported problem.
     */
    fun typeNameOrNull(type: KSType): TypeName? = try {
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
    fun erasureKey(type: KSType): String =
        expandAliases(type).makeNotNullable().declaration.qualifiedName?.asString()
            ?: type.toString().substringBefore('<').removeSuffix("?")

    fun ownerIsSubtypeOf(
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

    fun accepts(expected: KSType, provided: KSType, symbol: KSNode): Boolean {
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
    fun sameType(first: KSType?, second: KSType?): Boolean {
        if (first == null || second == null) return first == null && second == null
        val resolvedFirst = expandAliases(first)
        val resolvedSecond = expandAliases(second)
        return !resolvedFirst.isError &&
                !resolvedSecond.isError &&
                resolvedFirst.isAssignableFrom(resolvedSecond) &&
                resolvedSecond.isAssignableFrom(resolvedFirst)
    }

    private fun symbolDescription(symbol: KSNode): String = when (symbol) {
        is KSPropertyDeclaration -> "property ${symbol.simpleName.asString()}"
        is KSFunctionDeclaration -> "function ${symbol.simpleName.asString()}"
        else -> symbol.toString()
    }

    /**
     * Returns the validator invocation prefix, or `null` when no validator is
     * configured or a diagnostic was reported.
     */
    fun validator(
        property: KSPropertyDeclaration,
        propertyName: String,
        validatorType: KSType?,
        valueType: KSType,
    ): Instantiation? {
        if (validatorType == null) return null
        val resolvedValidator = expandAliases(validatorType)
        if (resolvedValidator.isUnit()) return null
        if (resolvedValidator.isError) {
            reportUnresolved(property, "The validator of property $propertyName is not resolvable yet.")
            return null
        }
        val declaration = resolvedValidator.declaration as? KSClassDeclaration ?: run {
            report(property, "The validator of property $propertyName must be a class or object.")
            return null
        }
        if (declaration.typeParameters.isNotEmpty()) {
            report(property, "The validator of property $propertyName must not declare type parameters.")
            return null
        }
        val dslValidatorArguments = findSuperTypeArguments(declaration, property, DSL_VALIDATOR_NAME)
        if (dslValidatorArguments == null) {
            report(property, "The validator of property $propertyName must implement DslValidator.")
            return null
        }
        val validatedType = dslValidatorArguments.singleOrNull()?.let { expandAliases(it) }
        if (validatedType == null || !validatedType.isAssignableFrom(valueType)) {
            report(
                property,
                "The validator of property $propertyName validates a type that does not accept ${
                    typeNameOrNull(valueType) ?: "the property"
                } values."
            )
            return null
        }
        val prefix = instantiation(property, propertyName, declaration, "validator") ?: return null
        return prefix
    }

    /**
     * Returns the mapper info, `null` when no mapper is configured or a
     * diagnostic was reported; callers distinguish the two cases through
     * [Checker.valid].
     */
    fun mapper(
        property: KSPropertyDeclaration,
        propertyName: String,
        mapperType: KSType?,
        propertyType: KSType,
    ): MapperInfo? {
        if (mapperType == null) return null
        val resolvedMapper = expandAliases(mapperType)
        if (resolvedMapper.isUnit()) return null
        if (resolvedMapper.isError) {
            reportUnresolved(property, "The mapper of property $propertyName is not resolvable yet.")
            return null
        }
        val declaration = resolvedMapper.declaration as? KSClassDeclaration ?: run {
            report(property, "The mapper of property $propertyName must be a class or object.")
            return null
        }
        if (declaration.typeParameters.isNotEmpty()) {
            report(property, "The mapper of property $propertyName must not declare type parameters.")
            return null
        }
        val dslMapperArguments = findSuperTypeArguments(declaration, property, DSL_MAPPER_NAME)
        if (dslMapperArguments == null) {
            report(property, "The mapper of property $propertyName must implement DslMapper.")
            return null
        }
        if (dslMapperArguments.size != 2 || dslMapperArguments.any { it == null }) {
            report(
                property,
                "The mapper of property $propertyName must implement DslMapper with two type arguments."
            )
            return null
        }
        val storedType = expandAliases(dslMapperArguments[0]!!)
        val valueType = expandAliases(dslMapperArguments[1]!!)
        // The value type feeds both directions of the mapping: the setter
        // passes property values into `toStored`, and the getter assigns
        // the result of `toValue` back to the property type.
        if (!valueType.isAssignableFrom(propertyType) || !propertyType.isAssignableFrom(valueType)) {
            report(
                property,
                "The mapper of property $propertyName maps a type that does not accept ${
                    typeNameOrNull(propertyType) ?: "the property"
                } values."
            )
            return null
        }
        val target = instantiation(property, propertyName, declaration, "mapper") ?: return null
        val storageTypeName = renderTypeName(property, storedType) ?: return null
        return MapperInfo(target, storageTypeName, storedType)
    }

    private fun instantiation(
        property: KSPropertyDeclaration,
        propertyName: String,
        declaration: KSClassDeclaration,
        role: String,
    ): Instantiation? {
        if (declaration.qualifiedName == null) {
            report(property, "The $role of property $propertyName has no qualified name.")
            return null
        }
        var containingDeclaration: KSDeclaration? = declaration
        while (containingDeclaration != null) {
            when (containingDeclaration.getVisibility()) {
                Visibility.PRIVATE, Visibility.PROTECTED, Visibility.LOCAL -> {
                    report(property, "The $role of property $propertyName is not visible from generated code.")
                    return null
                }

                else -> {}
            }
            containingDeclaration = containingDeclaration.parentDeclaration
        }
        return when {
            declaration.isCompanionObject || declaration.classKind == ClassKind.OBJECT ->
                Instantiation(declaration.toClassName(), construct = false)

            declaration.classKind == ClassKind.CLASS -> {
                if (
                    Modifier.ABSTRACT in declaration.modifiers ||
                    Modifier.SEALED in declaration.modifiers ||
                    Modifier.INNER in declaration.modifiers
                ) {
                    report(property, "The $role of property $propertyName must be a concrete, non-inner class.")
                    return null
                }
                val constructor = declaration.primaryConstructor
                val constructorVisibility = constructor?.getVisibility()
                val callable = constructor != null &&
                        constructor.parameters.all { it.hasDefault || it.isVararg } &&
                        constructorVisibility != Visibility.PRIVATE &&
                        constructorVisibility != Visibility.PROTECTED
                if (callable) Instantiation(declaration.toClassName(), construct = true) else {
                    report(
                        property,
                        "The $role of property $propertyName is a class without an accessible constructor callable with no arguments."
                    )
                    null
                }
            }

            else -> {
                report(property, "The $role of property $propertyName must be a class or object.")
                null
            }
        }
    }

    /**
     * Renders a compile-time constant written in string form as a Kotlin
     * literal expression of the property type. String constants are rendered
     * by KotlinPoet, which normalizes their line endings and trims a trailing
     * newline in property initializers; a constant that contains a surrogate
     * code unit cannot go through KotlinPoet and is rendered verbatim.
     */
    fun literal(property: KSPropertyDeclaration, propertyName: String, initial: String, type: KSType): CodeBlock? {
        val literalClass = expandAliases(type).makeNotNullable().declaration
        val format = initialFormats.firstOrNull { literalClass.isClass(it.type) }
        if (format == null) {
            report(
                property,
                "@DslValue.initial of property $propertyName supports " +
                        initialFormats.dropLast(1).joinToString(", ") { it.type.simpleName } +
                        ", and ${initialFormats.last().type.simpleName} constants."
            )
            return null
        }
        return format.parse(initial) ?: run {
            report(
                property,
                "@DslValue.initial of property $propertyName is \"$initial\", which is ${format.detail}."
            )
            null
        }
    }

    /**
     * Searches the supertype hierarchy of [declaration] for [qualifiedName]
     * and returns the type arguments of the match as seen from the perspective
     * of [declaration], substituting the type parameters of any generic bases
     * along the way. Returns `null` when the supertype is not implemented or
     * an unresolvable type was met.
     */
    fun findSuperTypeArguments(
        declaration: KSClassDeclaration,
        symbol: KSNode,
        qualifiedName: String,
    ): List<KSType?>? {
        val pending = ArrayDeque<Pair<KSClassDeclaration, Map<KSTypeParameter, KSType>>>()
        pending += declaration to emptyMap()
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val (current, environment) = pending.removeFirst()
            val currentName = current.qualifiedName?.asString() ?: continue
            if (!visited.add(currentName)) continue
            for (superType in current.superTypes) {
                val resolved = expandAliases(superType.resolve())
                if (resolved.isError) {
                    reportUnresolved(symbol, "A supertype of ${symbolDescription(symbol)} is not resolvable yet.")
                    return null
                }
                val superDeclaration = resolved.declaration as? KSClassDeclaration ?: continue
                if (superDeclaration.qualifiedName?.asString() == qualifiedName) {
                    return resolved.arguments.map { argument ->
                        argument.type?.resolve()?.let { substituteType(it, environment) }
                    }
                }
                val parameters = superDeclaration.typeParameters
                val nextEnvironment = parameters.zip(resolved.arguments)
                    .mapNotNull { (parameter, argument) ->
                        val argumentType = argument.type?.resolve() ?: return@mapNotNull null
                        parameter to substituteType(argumentType, environment)
                    }
                    .toMap()
                pending += superDeclaration to nextEnvironment
            }
        }
        return null
    }

}
