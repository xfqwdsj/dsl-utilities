package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Returns the validator invocation prefix, or `null` when no validator is
 * configured or a diagnostic was reported.
 */
internal fun Checker.validator(
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
    if (prefix.construct) logger.warn(
        "Class validators are deprecated; declare the validator as an object. Class support will be removed in 3.0.",
        property
    )
    return prefix
}

/**
 * Returns the mapper info, `null` when no mapper is
 * configured or a diagnostic was reported; callers compare
 * [Checker.diagnosticCount] across the call to distinguish the two cases.
 */
internal fun Checker.mapper(
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
    if (target.construct) logger.warn(
        "Class mappers are deprecated; declare the mapper as an object. Class support will be removed in 3.0.",
        property
    )
    val storageTypeName = renderTypeName(property, storedType) ?: return null
    return MapperInfo(target, storageTypeName, storedType)
}

/** Resolves a provider whose result can initialize the declared property. */
internal fun Checker.initialProvider(
    property: KSPropertyDeclaration,
    propertyName: String,
    providerType: KSType?,
    valueType: KSType,
): Instantiation? {
    if (providerType == null) return null
    val resolvedProvider = expandAliases(providerType)
    if (resolvedProvider.isUnit()) return null
    if (resolvedProvider.isError) {
        reportUnresolved(property, "The initial provider of property $propertyName is not resolvable yet.")
        return null
    }
    val declaration = resolvedProvider.declaration as? KSClassDeclaration ?: run {
        report(property, "The initial provider of property $propertyName must be a class or object.")
        return null
    }
    if (declaration.typeParameters.isNotEmpty()) {
        report(property, "The initial provider of property $propertyName must not declare type parameters.")
        return null
    }
    val arguments = findSuperTypeArguments(declaration, property, DSL_INITIAL_PROVIDER_NAME)
    if (arguments == null) {
        report(property, "The initial provider of property $propertyName must implement DslInitialProvider.")
        return null
    }
    val providedType = arguments.singleOrNull()?.let { expandAliases(it) }
    if (providedType == null || !valueType.isAssignableFrom(providedType)) {
        report(
            property,
            "The initial provider of property $propertyName returns a type that is not assignable to the property."
        )
        return null
    }
    return singletonInstantiation(property, propertyName, declaration, "initial provider")
}

internal fun Checker.hook(
    property: KSPropertyDeclaration,
    propertyName: String,
    hookType: KSType?,
    valueType: KSType,
    contractName: String,
): Instantiation? {
    if (hookType == null) return null
    val resolvedHook = expandAliases(hookType)
    if (resolvedHook.isUnit()) return null
    if (resolvedHook.isError) {
        reportUnresolved(property, "The hook of property $propertyName is not resolvable yet.")
        return null
    }
    val declaration = resolvedHook.declaration as? KSClassDeclaration ?: run {
        report(property, "The hook of property $propertyName must be a class or object.")
        return null
    }
    if (declaration.typeParameters.isNotEmpty()) {
        report(property, "The hook of property $propertyName must not declare type parameters.")
        return null
    }
    val arguments = findSuperTypeArguments(declaration, property, contractName)
    if (arguments == null) {
        report(property, "The hook of property $propertyName must implement ${contractName.substringAfterLast('.')}.")
        return null
    }
    val acceptedType = arguments.singleOrNull()?.let { expandAliases(it) }
    if (acceptedType == null || !acceptedType.isAssignableFrom(valueType)) {
        report(property, "The hook of property $propertyName does not accept the property values.")
        return null
    }
    return singletonInstantiation(property, propertyName, declaration, "hook")
}

internal fun Checker.buildHook(spec: KSClassDeclaration, hookType: KSType?): Instantiation? {
    if (hookType == null) return null
    val type = expandAliases(hookType)
    if (type.isUnit()) return null
    if (type.isError) {
        reportUnresolved(spec, "The build hook of ${spec.simpleName.asString()} is not resolvable yet.")
        return null
    }
    val declaration = type.declaration as? KSClassDeclaration
    if (declaration == null || (!declaration.isCompanionObject && declaration.classKind != ClassKind.OBJECT)) {
        report(spec, "@DslBuilder.buildHook must be an object or companion object.")
        return null
    }
    val accepted = findSuperTypeArguments(declaration, spec, DSL_BUILD_HOOK_NAME)
        ?.singleOrNull()?.let(::expandAliases)
    if (accepted == null || !accepted.isAssignableFrom(spec.asType(emptyList()))) {
        report(spec, "@DslBuilder.buildHook must implement DslBuildHook accepting ${spec.simpleName.asString()}.")
        return null
    }
    if (declaration.qualifiedName == null || !isVisible(declaration)) {
        report(spec, "@DslBuilder.buildHook is not visible from generated code.")
        return null
    }
    return Instantiation(declaration.toClassName(), construct = false)
}

private fun Checker.singletonInstantiation(
    property: KSPropertyDeclaration,
    propertyName: String,
    declaration: KSClassDeclaration,
    role: String,
): Instantiation? {
    if (!declaration.isCompanionObject && declaration.classKind != ClassKind.OBJECT) {
        report(property, "The $role of property $propertyName must be an object or companion object.")
        return null
    }
    return instantiation(property, propertyName, declaration, role)
}

internal fun Checker.instantiation(
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
        if (!isAccessibleFromGeneratedCode(containingDeclaration)) {
            report(property, "The $role of property $propertyName is not visible from generated code.")
            return null
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
            val callable = declaration.getConstructors().any { constructor ->
                constructor.parameters.all { it.hasDefault || it.isVararg } &&
                        isAccessibleFromGeneratedCode(constructor)
            }
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
internal fun Checker.literal(
    property: KSPropertyDeclaration,
    propertyName: String,
    initial: String,
    type: KSType
): CodeBlock? {
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
internal fun Checker.findSuperTypeArguments(
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
