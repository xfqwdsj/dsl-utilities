package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Child scopes are declared as `@DslChild` functions whose last parameter
 * is a function type with the child DslBuilder interface as receiver and
 * whose other parameters carry the required properties of the child; the
 * processor generates the function body.
 */
internal fun DslProcessor.childScope(
    name: String,
    function: KSFunctionDeclaration,
    environment: Map<KSTypeParameter, KSType>,
    checker: Checker,
): ChildScope? {
    if (function.extensionReceiver != null) {
        checker.report(function, "@DslChild function $name must not declare an extension receiver.")
        return null
    }
    if (function.typeParameters.isNotEmpty()) {
        checker.report(function, "@DslChild function $name must not declare type parameters.")
        return null
    }
    val functionReturnType = function.returnType?.resolve()
        ?.let { checker.expandAliases(checker.substituteType(it, environment)) }
    if (functionReturnType?.isError == true) {
        checker.reportUnresolved(function, "The return type of @DslChild function $name is not resolvable yet.")
        return null
    }
    if (functionReturnType?.isUnit() != true) {
        checker.report(function, "@DslChild function $name must return Unit.")
        return null
    }
    val parameters = function.parameters
    if (parameters.isEmpty()) {
        checker.report(
            function,
            "@DslChild function $name declares no parameters; the last parameter must be a function type with the child DslBuilder interface as receiver."
        )
        return null
    }
    val blockParameter = parameters.last()
    val blockName = blockParameter.name?.asString() ?: run {
        checker.report(function, "The block parameter of @DslChild function $name has no name.")
        return null
    }
    if (blockParameter.isVararg) {
        checker.report(function, "The block parameter of @DslChild function $name must not be vararg.")
        return null
    }
    val declaredBlockType = blockParameter.type.resolve()
    if (declaredBlockType.isError) {
        checker.reportUnresolved(function, "The last parameter of @DslChild function $name is not resolvable yet.")
        return null
    }
    // The block type of a child scope inherited from a generic base
    // carries the base's type parameters; substituting them resolves the
    // receiver from the perspective of the analyzed interface. Shape checks
    // read the alias target because substitution does not carry over the
    // receiver and suspend markers, while a bare type parameter only has a
    // shape once it is substituted.
    val substitutedBlockType = checker.substituteType(declaredBlockType, environment)
    val bareParameter = declaredBlockType.declaration is KSTypeParameter
    val blockShape = if (bareParameter) {
        checker.aliasTarget(substitutedBlockType)
    } else {
        checker.aliasTarget(declaredBlockType)
    }
    if (blockShape.isMarkedNullable) {
        checker.report(function, "The block parameter of @DslChild function $name must not be nullable.")
        return null
    }
    val isReceiverStyle = blockShape.annotations.any { it.isMarker(EXTENSION_FUNCTION_TYPE) }
    val isSuspend = blockShape.isSuspendFunctionType
    val blockType = if (bareParameter) {
        checker.expandAliases(substitutedBlockType)
    } else {
        checker.substituteType(checker.expandAliases(declaredBlockType), environment)
    }
    val arguments = blockType.arguments
    // A star projection that the alias target uses cannot be rendered; a
    // kept alias always carries one somewhere in its chain. Report the
    // projection itself instead of the shape error it would otherwise hit.
    val hasUnboundProjection = blockType.hasUnboundProjection()
    if (hasUnboundProjection && (blockShape.isFunctionType || isSuspend)) {
        checker.report(
            function,
            "The last parameter of @DslChild function $name has a star-projected type argument, which is not supported."
        )
        return null
    }
    if (isReceiverStyle && arguments.size > 2) {
        checker.report(
            function,
            "The last parameter of @DslChild function $name must not declare value parameters."
        )
        return null
    }
    if ((!blockShape.isFunctionType && !isSuspend) || arguments.size != (if (isReceiverStyle) 2 else 1)) {
        // The dedicated message fits when a leading parameter is the
        // misplaced block: its receiver is a DslBuilder interface that
        // requires the last parameter. Otherwise the last parameter is
        // most likely the block itself with a type that does not fit.
        val lastParameterName = parameters.lastOrNull()?.name?.asString()
        val misplacedBlock = !blockShape.isFunctionType && !isSuspend && lastParameterName != null &&
                parameters.dropLast(1).any { parameter ->
                    val declaredParameterType = parameter.type.resolve()
                    val parameterShape = checker.aliasTarget(
                        if (declaredParameterType.declaration is KSTypeParameter) {
                            checker.substituteType(declaredParameterType, environment)
                        } else {
                            declaredParameterType
                        }
                    )
                    if (
                        !parameterShape.isFunctionType ||
                        parameterShape.annotations.none { it.isMarker(EXTENSION_FUNCTION_TYPE) }
                    ) {
                        return@any false
                    }
                    val receiverType = parameterShape.arguments.firstOrNull()?.type?.resolve() ?: return@any false
                    val receiver = checker.expandAliases(receiverType).declaration as? KSClassDeclaration
                        ?: return@any false
                    receiver.annotation(DSL_BUILDER_ANNOTATION) != null &&
                            requiresProperty(receiver, lastParameterName)
                }
        checker.report(
            function,
            if (misplacedBlock) {
                "The block parameter of @DslChild function $name must be the last parameter."
            } else {
                "The last parameter of @DslChild function $name must be a function type with the child DslBuilder interface as receiver and a Unit return type."
            }
        )
        return null
    }
    if (!isReceiverStyle) {
        checker.report(
            function,
            "The last parameter of @DslChild function $name must take the child DslBuilder interface as a receiver."
        )
        return null
    }
    if (isSuspend || Modifier.SUSPEND in function.modifiers) {
        // The DSL block that invokes the child scope is never suspend, so
        // neither a suspend block nor a suspend child scope function can
        // be called from it.
        checker.report(
            function,
            "@DslChild function $name must not be suspend or take a suspend block."
        )
        return null
    }
    val blockReturnType = arguments.last().type?.resolve()?.let { checker.expandAliases(it) }
    if (blockReturnType == null ||
        !blockReturnType.isUnit() ||
        blockReturnType.isMarkedNullable
    ) {
        checker.report(
            function,
            "The last parameter of @DslChild function $name must return Unit."
        )
        return null
    }
    val receiverType = arguments[0].type?.resolve()?.let { checker.expandAliases(it) } ?: run {
        checker.report(
            function,
            "The last parameter of @DslChild function $name must be a function type with the child DslBuilder interface as receiver."
        )
        return null
    }
    if (receiverType.isMarkedNullable) {
        checker.report(function, "The receiver of @DslChild function $name must not be nullable.")
        return null
    }
    val childDeclaration = receiverType.declaration as? KSClassDeclaration ?: run {
        checker.report(function, "The receiver of @DslChild function $name must be a DslBuilder interface.")
        return null
    }
    val child = resolveChild(childDeclaration, checker) ?: return null
    val declaredParameters = parameters.dropLast(1)
    if (declaredParameters.size != child.required.size) {
        checker.report(
            function,
            "@DslChild function $name declares ${declaredParameters.size} value parameters for a child with ${child.required.size} required properties."
        )
        return null
    }
    val scopeParameters = mutableListOf<Parameter>()
    for ((parameter, required) in declaredParameters.zip(child.required)) {
        val parameterName = parameter.name?.asString() ?: run {
            checker.report(function, "A parameter of @DslChild function $name has no name.")
            return null
        }
        if (parameter.isVararg) {
            checker.report(function, "Parameter $parameterName of @DslChild function $name must not be vararg.")
            return null
        }
        val declarationParameterType = parameter.type.resolve()
        val parameterType = checker.substituteType(declarationParameterType, environment)
        val parameterTypeName = checker.renderTypeName(function, parameterType, declarationParameterType)
            ?: return null
        if (parameterName != required.name || !required.type.isAssignableFrom(parameterType)) {
            checker.report(
                function,
                "Parameter $parameterName of @DslChild function $name does not match required property ${required.name} of type ${required.typeName} of the child."
            )
            return null
        }
        scopeParameters += Parameter(parameterName, parameterTypeName)
    }
    val blockTypeName = checker.renderTypeName(
        function,
        checker.substituteType(declaredBlockType, environment),
        declaredBlockType,
    ) ?: return null
    return ChildScope(name, scopeParameters, blockName, blockTypeName, child)
}

/**
 * Resolves a DslBuilder interface referenced as a child scope or as a list
 * child. The builder and result classes of the child follow the naming
 * rules of generated top-level classes, so children resolve across module
 * boundaries as well.
 */
internal fun DslProcessor.resolveChild(declaration: KSClassDeclaration, checker: Checker): ChildSpec? {
    val specName = declaration.simpleName.asString()
    val annotation = declaration.annotation(DSL_BUILDER_ANNOTATION)
    if (annotation == null) {
        checker.report(declaration, "$specName must be annotated with @DslBuilder to be used as a child.")
        return null
    }
    if (declaration.classKind != ClassKind.INTERFACE) {
        checker.report(
            declaration,
            "@DslBuilder applies to interfaces, but $specName is a ${declaration.classKind}."
        )
        return null
    }
    if (declaration.typeParameters.isNotEmpty()) {
        checker.report(
            declaration,
            "Child DslBuilder interface $specName must not declare type parameters."
        )
        return null
    }
    if (declaration.qualifiedName == null) {
        checker.report(declaration, "Child $specName has no qualified name.")
        return null
    }
    val names = Names.of(specName, annotation)
    val packageName = declaration.packageName.asString()
    val builderVisibility = effectiveVisibility(declaration, checker) ?: return null
    val diagnostics = checker.diagnosticCount
    val resultSupertype = resolveResultSupertype(declaration, annotation, checker, checkSubclassing = false)
    if (checker.diagnosticCount != diagnostics) return null
    val resultVisibility = restrictiveVisibility(listOfNotNull(builderVisibility, resultSupertype?.visibility))
    if (resultVisibility == KModifier.INTERNAL && !declaration.isDeclaredInThisModule()) {
        checker.report(
            declaration,
            "Child $specName has an internal generated result that is not accessible from this module."
        )
        return null
    }
    val hierarchy = Hierarchy(checker, reportedStarProjections)
    val required = mutableListOf<RequiredProperty>()
    for (member in hierarchy.allProperties(declaration).filter { hierarchy.isAbstract(it.declaration) }) {
        val property = member.declaration
        if (property.isMutable) continue
        val declarationType = property.type.resolve()
        val type = checker.substituteType(declarationType, member.environment)
        val typeName = checker.renderTypeName(property, type, declarationType) ?: return null
        required += RequiredProperty(
            property.simpleName.asString(),
            typeName,
            checker.expandAliases(type),
            null,
            ""
        )
    }
    return ChildSpec(
        functionName = decapitalize(derivedBaseName(specName)),
        specTypeName = declaration.toClassName(),
        builderType = names.builderType(packageName),
        resultType = names.resultType(packageName),
        resultSupertype = resultSupertype?.type,
        specType = declaration.asType(emptyList()),
        builderVisibility = builderVisibility,
        resultVisibility = resultVisibility,
        required = required,
        requiresConfiguration = hierarchy.abstractFunctions(declaration)
            .any { it.declaration.annotation(DSL_CHILD_ANNOTATION) != null },
    )
}

/**
 * Resolves and validates the optional interface implemented by a generated
 * result.
 */
internal fun resolveResultSupertype(
    spec: KSClassDeclaration,
    annotation: KSAnnotation?,
    checker: Checker,
    checkSubclassing: Boolean,
): ResultSupertype? {
    val specName = spec.simpleName.asString()
    val rawType = annotation?.type("supertype") ?: return null
    val type = checker.expandAliases(rawType)
    if (type.isUnit()) return null
    if (type.isError) {
        checker.reportUnresolved(spec, "@DslBuilder.supertype of $specName is not resolvable yet.")
        return null
    }
    val declaration = type.declaration as? KSClassDeclaration
    if (declaration?.classKind != ClassKind.INTERFACE) {
        checker.report(spec, "@DslBuilder.supertype of $specName must be an interface.")
        return null
    }
    if (declaration.typeParameters.isNotEmpty()) {
        checker.report(spec, "@DslBuilder.supertype of $specName must not be generic.")
        return null
    }
    // Kotlin requires direct subclasses of a sealed type to live in the
    // same package and module as the sealed declaration; the generated
    // result is emitted in the specification's package and module.
    if (checkSubclassing && Modifier.SEALED in declaration.modifiers) {
        val samePackage = declaration.packageName.asString() == spec.packageName.asString()
        val sameModule = declaration.isDeclaredInThisModule()
        if (!samePackage || !sameModule) {
            checker.report(
                spec,
                "@DslBuilder.supertype of $specName is a sealed interface, so the generated result must be in its package and module."
            )
            return null
        }
    }
    val typeName = checker.renderTypeName(spec, type) ?: return null
    val visibility = effectiveVisibility(declaration, checker) ?: return null
    return ResultSupertype(type, declaration, typeName, visibility)
}

internal fun restrictiveVisibility(visibilities: Iterable<KModifier>): KModifier =
    if (KModifier.INTERNAL in visibilities) KModifier.INTERNAL else KModifier.PUBLIC

/**
 * Returns `true` when a supertype property of type [expectedType] can be
 * overridden by the generated concrete result of [child]. The result class
 * is generated in the same round, so its declared result supertype and its
 * qualified name stand in for the class itself; the result is a non-null
 * class, so `Any` accepts it.
 */
internal fun acceptsChildResult(expectedType: KSType, child: ChildSpec, checker: Checker): Boolean {
    val resolvedExpected = checker.expandAliases(expectedType)
    val expectedName = resolvedExpected.makeNotNullable().declaration.qualifiedName?.asString()
    return expectedName == ANY.canonicalName ||
            expectedName == child.resultType.canonicalName ||
            child.resultSupertype?.let { resolvedExpected.isAssignableFrom(it) } == true
}

/**
 * Returns the visibility the generated declarations need to reference the
 * specification from the generated top-level file: the most restrictive
 * visibility found along the declaration chain, or `null` after
 * reporting when no generated declaration can reference the spec at all.
 */
internal fun effectiveVisibility(spec: KSDeclaration, checker: Checker): KModifier? {
    var internal = false
    var declaration: KSDeclaration? = spec
    while (declaration != null) {
        when (declaration.getVisibility()) {
            Visibility.PRIVATE, Visibility.PROTECTED, Visibility.LOCAL -> {
                checker.report(
                    spec,
                    "The DslBuilder interface ${spec.simpleName.asString()} must be visible from the package level, but it is hidden by ${declaration.simpleName.asString()}."
                )
                return null
            }

            Visibility.INTERNAL -> internal = true
            else -> {}
        }
        declaration = declaration.parentDeclaration
    }
    return if (internal) KModifier.INTERNAL else KModifier.PUBLIC
}
