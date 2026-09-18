package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
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
    return prefix
}

/**
 * Returns the mapper info, `null` when no mapper is configured or a
 * diagnostic was reported; callers compare [Checker.diagnosticCount]
 * across the call to distinguish the two cases.
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
    val storageTypeName = renderTypeName(property, storedType) ?: return null
    return MapperInfo(target, storageTypeName, storedType)
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
        if (!containingDeclaration.isAccessibleFromGeneratedCode()) {
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
                val visibility = constructor.getVisibility()
                constructor.parameters.all { it.hasDefault || it.isVararg } &&
                        visibility != Visibility.PRIVATE &&
                        visibility != Visibility.PROTECTED &&
                        visibility != Visibility.LOCAL &&
                        (visibility != Visibility.INTERNAL || declaration.isDeclaredInThisModule())
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
