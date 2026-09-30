package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/** Resolves the single value accepted by a generated DSL write function. */
private fun writeParameter(
    function: KSFunctionDeclaration,
    environment: Map<KSTypeParameter, KSType>,
    annotationName: String,
    checker: Checker,
): Pair<String, KSType>? {
    val name = function.simpleName.asString()
    if (function.extensionReceiver != null || function.typeParameters.isNotEmpty() || function.parameters.size != 1) {
        checker.report(
            function,
            "$annotationName function $name must have one value parameter, no receiver and no type parameters.",
        )
        return null
    }
    if (Modifier.SUSPEND in function.modifiers) {
        checker.report(function, "$annotationName function $name must not be suspend.")
        return null
    }
    val returnType = function.returnType?.resolve()
        ?.let { checker.expandAliases(checker.substituteType(it, environment)) }
    if (returnType?.isError == true) {
        checker.reportUnresolved(function, "The return type of $annotationName function $name is not resolvable yet.")
        return null
    }
    if (returnType?.isUnit() != true) {
        checker.report(function, "$annotationName function $name must return Unit.")
        return null
    }
    val parameter = function.parameters.single()
    val parameterName = parameter.name?.asString()
    if (parameterName == null || parameter.isVararg || parameter.hasDefault) {
        checker.report(
            function,
            "$annotationName function $name must declare a named, non-vararg parameter without a default.",
        )
        return null
    }
    val type = checker.substituteType(parameter.type.resolve(), environment)
    if (type.isError) {
        checker.reportUnresolved(
            function,
            "The parameter type of $annotationName function $name is not resolvable yet.",
        )
        return null
    }
    return parameterName to type
}

/** A function command writes a result property whose name is explicit. */
private fun resultName(
    function: KSFunctionDeclaration,
    annotation: KSAnnotation,
    annotationName: String,
    checker: Checker,
): String? {
    val name = annotation.string("resultName").orEmpty()
    if (name.isEmpty() || !isSimpleIdentifier(name)) {
        checker.report(
            function,
            "$annotationName.resultName of ${function.simpleName.asString()} must be a simple identifier.",
        )
        return null
    }
    return name
}

/** Builds the model for a nullable last-write-wins DSL setter. */
internal fun valueFunction(
    function: KSFunctionDeclaration,
    environment: Map<KSTypeParameter, KSType>,
    annotation: KSAnnotation,
    checker: Checker,
): ValueProperty? {
    val name = resultName(function, annotation, "@DslValue", checker) ?: return null
    if (annotation.providedString("initial") != null || annotation.boolean("required") == true ||
        listOf("provider", "hook", "validator", "mapper").any { annotation.type(it)?.isUnit() == false } ||
        !annotation.string("message").isNullOrEmpty()
    ) {
        checker.report(function, "@DslValue function ${function.simpleName.asString()} only accepts resultName.")
        return null
    }
    val (parameterName, type) = writeParameter(function, environment, "@DslValue", checker) ?: return null
    val resolved = checker.expandAliases(type)
    if (!resolved.isMarkedNullable) {
        checker.report(function, "@DslValue function ${function.simpleName.asString()} must accept a nullable value.")
        return null
    }
    val typeName =
        checker.renderTypeName(function.parameters.single(), type, function.parameters.single().type.resolve())
            ?: return null
    return ValueProperty(
        name = name,
        typeName = typeName,
        type = resolved,
        storageTypeName = typeName,
        initialValue = CodeBlock.of("null"),
        initialProvider = null,
        mapper = null,
        hook = null,
        validator = null,
        message = checker.message(annotation, name),
        required = false,
        setterName = function.simpleName.asString(),
        setterParameterName = parameterName,
    )
}

/** Builds the model for a DSL function that appends one list element. */
internal fun listFunction(
    function: KSFunctionDeclaration,
    environment: Map<KSTypeParameter, KSType>,
    annotation: KSAnnotation,
    checker: Checker,
): ListProperty? {
    val name = resultName(function, annotation, "@DslList", checker) ?: return null
    if (annotation.typeArray("children").isNotEmpty() || !annotation.string("scopeName").isNullOrEmpty() ||
        listOf("hook", "validator").any { annotation.type(it)?.isUnit() == false } ||
        !annotation.string("message").isNullOrEmpty()
    ) {
        checker.report(function, "@DslList function ${function.simpleName.asString()} only accepts resultName.")
        return null
    }
    val (parameterName, type) = writeParameter(function, environment, "@DslList", checker) ?: return null
    val resolved = checker.expandAliases(type)
    val typeName =
        checker.renderTypeName(function.parameters.single(), type, function.parameters.single().type.resolve())
            ?: return null
    val listType = checker.resolver.getClassDeclarationByName(
        checker.resolver.getKSNameFromString(MUTABLE_LIST.canonicalName),
    ) ?: run {
        checker.reportUnresolved(function, "MutableList is not resolvable yet.")
        return null
    }
    val argument = checker.resolver.getTypeArgument(
        checker.resolver.createKSTypeReferenceFromKSType(resolved), Variance.INVARIANT,
    )
    return ListProperty(
        name = name,
        typeName = MUTABLE_LIST.parameterizedBy(typeName),
        type = listType.asType(listOf(argument)),
        elementTypeName = typeName,
        elementType = resolved,
        validator = null,
        hook = null,
        message = checker.message(annotation, name),
        children = emptyList(),
        scopeName = "",
        appendName = function.simpleName.asString(),
        appendParameterName = parameterName,
    )
}
