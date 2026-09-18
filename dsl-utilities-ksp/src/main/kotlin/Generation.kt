package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Generates the code for one spec. Returns `false` when the spec should be
 * deferred to a later round because it only references unresolvable types;
 * diagnostics for genuine errors are reported before returning `true`.
 */
internal fun DslProcessor.generate(spec: KSClassDeclaration, resolver: Resolver): Boolean {
    val specName = spec.simpleName.asString()
    val packageName = spec.packageName.asString()
    val annotation = spec.annotation(DSL_BUILDER_ANNOTATION)
    val names = Names.of(specName, annotation)

    if (spec.classKind != ClassKind.INTERFACE) {
        logger.error("@DslBuilder applies to interfaces, but $specName is a ${spec.classKind}.", spec)
        return true
    }
    if (names.resultName == specName || names.builderName == specName) {
        val reused = listOfNotNull(
            "resultName".takeIf { names.resultName == specName },
            "builderName".takeIf { names.builderName == specName },
        )
        logger.error(
            "@DslBuilder of $specName must not reuse the interface name for ${reused.joinToString(" and ")}.",
            spec,
        )
        return true
    }
    if (names.resultName == names.builderName) {
        logger.error("The result and builder names of $specName must differ from each other.", spec)
        return true
    }
    if (names.generateFunction && names.functionName in setOf(names.resultName, names.builderName)) {
        val conflicting = listOfNotNull(
            "resultName".takeIf { names.functionName == names.resultName },
            "builderName".takeIf { names.functionName == names.builderName },
        )
        logger.error(
            "@DslBuilder.functionName of $specName must differ from ${conflicting.joinToString(" and ")}, so calls resolve to the generated function.",
            spec,
        )
        return true
    }
    if (!names.generateFunction && names.functionName.isNotEmpty()) {
        logger.error("@DslBuilder.functionName of $specName requires generateFunction.", spec)
        return true
    }
    val invalidNames = listOfNotNull(
        "resultName (${names.resultName})".takeIf { !isSimpleIdentifier(names.resultName) },
        "builderName (${names.builderName})".takeIf { !isSimpleIdentifier(names.builderName) },
    )
    if (invalidNames.isNotEmpty()) {
        logger.error(
            "@DslBuilder of $specName must use a simple identifier for ${invalidNames.joinToString(" and ")}.",
            spec,
        )
        return true
    }
    if (names.generateFunction && !isSimpleIdentifier(names.functionName)) {
        logger.error("@DslBuilder.functionName of $specName must be a simple identifier.", spec)
        return true
    }

    val checker = Checker(resolver, logger, packageName)
    val specQualifiedName = spec.qualifiedName?.asString()
    if (specQualifiedName == null) {
        checker.report(
            spec,
            "@DslBuilder applies to declarations with a qualified name, but $specName is local or anonymous."
        )
        return handled(checker)
    }
    val specTypeName = spec.toClassName()
    if (spec.typeParameters.isNotEmpty()) {
        checker.report(
            spec,
            "DslBuilder interfaces must not declare type parameters; the generated builder and result classes do not carry them."
        )
        return handled(checker)
    }
    val visibility = effectiveVisibility(spec, checker) ?: return handled(checker)

    val requiredProperties = mutableListOf<RequiredProperty>()
    val valueProperties = mutableListOf<ValueProperty>()
    val listProperties = mutableListOf<ListProperty>()
    val childScopes = mutableListOf<ChildScope>()
    val hierarchy = Hierarchy(checker, reportedStarProjections)
    val specProperties = hierarchy.allProperties(spec)
    for (member in specProperties) {
        val property = member.declaration
        val name = property.simpleName.asString()
        val dslValue = property.annotation(DSL_VALUE_ANNOTATION)
        val dslList = property.annotation(DSL_LIST_ANNOTATION)

        if (!hierarchy.isAbstract(property)) {
            if (dslValue != null || dslList != null) {
                checker.report(
                    property,
                    "Property $name must be abstract for @DslValue or @DslList to generate its accessors."
                )
            }
            continue
        }
        if (property.extensionReceiver != null) {
            checker.report(property, "Property $name must not declare an extension receiver.")
            continue
        }
        if (dslValue != null && dslList != null) {
            checker.report(property, "Property $name must not be annotated with both @DslValue and @DslList.")
            continue
        }
        val declarationType = property.type.resolve()
        val type = checker.substituteType(declarationType, member.environment)
        if (dslList != null) {
            listProperty(name, property, type, declarationType, dslList, checker)?.let(listProperties::add)
        } else if (!property.isMutable) {
            requiredProperty(
                name,
                property,
                type,
                declarationType,
                dslValue,
                checker
            )?.let(requiredProperties::add)
        } else if (dslValue != null) {
            valueProperty(name, property, type, declarationType, dslValue, checker)?.let(valueProperties::add)
        } else {
            checker.report(property, "Property $name must be annotated with @DslValue or @DslList.")
        }
    }

    val abstractFunctions = hierarchy.abstractFunctions(spec)
    val specMemberNames = specProperties.mapTo(mutableSetOf()) { it.declaration.simpleName.asString() }
    for ((name, overloads) in abstractFunctions.groupBy { it.declaration.simpleName.asString() }) {
        if (overloads.size > 1) {
            checker.report(
                spec,
                "DslBuilder interface $specName overloads child-scope function $name; overloaded child-scope names are not supported."
            )
        }
    }
    for (member in hierarchy.allFunctions(spec)) {
        val function = member.declaration
        specMemberNames += function.simpleName.asString()
        if (function.isAbstract) continue
        if (function.simpleName.asString() == BUILD_FUNCTION &&
            function.parameters.isEmpty() &&
            function.extensionReceiver == null
        ) {
            checker.report(
                function,
                "The generated builder declares $BUILD_FUNCTION() to return the built result, so the DSL function $BUILD_FUNCTION() must take a parameter or use another name."
            )
        }
        if (function.annotation(DSL_CHILD_ANNOTATION) != null) {
            checker.report(
                function,
                "@DslChild function ${function.simpleName.asString()} declares a body; declare it abstract so the processor can generate the body."
            )
        }
    }
    for (member in abstractFunctions) {
        val function = member.declaration
        val name = function.simpleName.asString()
        if (function.annotation(DSL_CHILD_ANNOTATION) == null) {
            checker.report(
                function,
                "Function $name must be annotated with @DslChild; DslBuilder interfaces declare properties and @DslChild functions."
            )
            continue
        }
        childScope(name, function, member.environment, checker)?.let(childScopes::add)
    }

    val generatedPropertyNames = buildSet {
        requiredProperties.mapTo(this) { it.name }
        valueProperties.mapTo(this) { it.name }
        listProperties.mapTo(this) { it.name }
    }
    for (scope in childScopes) {
        if (scope.name in generatedPropertyNames) {
            checker.report(
                spec,
                "@DslChild function ${scope.name} conflicts with a DSL property of the same name."
            )
        }
    }

    val memberNames = hierarchy.functionNames(spec) +
            specProperties.map { it.declaration.simpleName.asString() }
    val childOwners = mutableMapOf<String, String>()
    for (property in listProperties) {
        for (child in property.children) {
            if (child.functionName in memberNames) {
                checker.report(
                    spec,
                    "Child helper ${child.functionName} of @DslList property ${property.name} conflicts with a DSL member."
                )
            }
            val previousOwner = childOwners.putIfAbsent(child.functionName, property.name)
            if (previousOwner != null && previousOwner != property.name) {
                checker.report(
                    spec,
                    "DslList properties $previousOwner and ${property.name} both generate child helper ${child.functionName}."
                )
            }
        }
    }

    if (!checker.valid) return handled(checker)

    val resultSupertype = resolveResultSupertype(spec, annotation, checker, checkSubclassing = true)
    if (!checker.valid) return handled(checker)
    val supertypeTypeName = resultSupertype?.typeName
    val supertypeOverrides = mutableSetOf<String>()
    val resultProperties = requiredProperties.map { it.name to it.typeName } +
            valueProperties.map { it.name to it.typeName } +
            listProperties.map { it.name to LIST.parameterizedBy(it.elementTypeName) } +
            childScopes.map { it.name to it.child.resultType }
    val resolvedResultPropertyTypes = buildMap {
        for (property in requiredProperties) put(property.name, property.type)
        for (property in valueProperties) put(property.name, property.type)
        for (property in listProperties) put(property.name, checker.immutableListType(property.elementType))
        // A child scope's property type is the generated concrete result
        // class, which is not resolvable while it is generated. Its declared
        // result supertype (when any) carries the assignability; the special
        // case in `acceptsChildResult` covers the rest.
        for (property in childScopes) put(property.name, property.child.resultSupertype)
    }

    // The generated result constructor takes every result property; a child
    // property carries a generated result class, which existing declarations
    // cannot name, so that position stays out of constructor comparisons.
    val childScopeNames = childScopes.mapTo(mutableSetOf()) { it.name }
    val constructorTypes = resultProperties.map { (name, _) ->
        if (name in childScopeNames) null else resolvedResultPropertyTypes[name]
    }

    if (resultSupertype != null) {
        val supertypeDeclaration = resultSupertype.declaration
        val childScopesByName = childScopes.associateBy { it.name }
        val resultPropertyTypes = resultProperties.toMap()
        for (member in hierarchy.allProperties(supertypeDeclaration)) {
            val property = member.declaration
            val name = property.simpleName.asString()
            if (property.extensionReceiver != null) {
                if (hierarchy.isAbstract(property)) {
                    checker.report(
                        spec,
                        "The result supertype ${supertypeDeclaration.simpleName.asString()} declares property $name, which the generated result cannot implement."
                    )
                }
                continue
            }
            val expectedType = checker.substituteType(property.type.resolve(), member.environment)
            val rendered = checker.renderTypeName(property, expectedType) ?: break
            val provided = resultPropertyTypes[name]
            val resolvedProvided = resolvedResultPropertyTypes[name]
            val compatible = provided == rendered ||
                    resolvedProvided?.let(expectedType::isAssignableFrom) == true ||
                    childScopesByName[name]?.let { acceptsChildResult(expectedType, it.child, checker) } == true
            when {
                // Result properties are read-only constructor values: a
                // mutable supertype member can never be overridden by one,
                // and an abstract one cannot be inherited either.
                property.isMutable && (provided != null || hierarchy.isAbstract(property)) ->
                    checker.report(
                        spec,
                        "The result supertype ${supertypeDeclaration.simpleName.asString()} declares mutable property $name, which the generated result cannot override."
                    )

                // A concrete supertype property keeps its default when the
                // DSL does not provide a member of the same name; an
                // abstract one has no default and must be provided.
                provided == null && hierarchy.isAbstract(property) ->
                    checker.report(
                        spec,
                        "The result supertype ${supertypeDeclaration.simpleName.asString()} declares property $name, which the DslBuilder interface does not provide."
                    )

                provided == null -> Unit

                !compatible ->
                    checker.report(
                        spec,
                        "Property $name of the result supertype has type $rendered, but the DslBuilder interface provides $provided."
                    )

                else -> supertypeOverrides += name
            }
        }
        for (member in hierarchy.allFunctions(supertypeDeclaration)) {
            val function = member.declaration
            if (function.extensionReceiver != null) {
                if (function.isAbstract) {
                    checker.report(
                        spec,
                        "The result supertype ${supertypeDeclaration.simpleName.asString()} declares function ${function.simpleName.asString()}, which the generated result cannot implement."
                    )
                }
                continue
            }
            val name = function.simpleName.asString()
            val componentIndex = name.removePrefix("component").toIntOrNull()
                ?.takeIf { name == "component$it" }
            val parameters = function.parameters.map { parameter ->
                checker.substituteType(parameter.type.resolve(), member.environment)
            }
            val returnType = function.returnType?.resolve()
                ?.let { checker.substituteType(it, member.environment) }
            // A `componentK` declaration shares the name and the empty
            // parameter list of the synthesized member for the K-th result
            // property; the synthesized `copy` carries default values and
            // cannot override a declaration with the same parameter types.
            val componentPosition = componentIndex?.takeIf { it in 1..resultProperties.size }
            val componentProperty = if (function.typeParameters.isEmpty() && parameters.isEmpty()) {
                componentPosition?.let { resultProperties[it - 1] }
            } else {
                null
            }
            val synthesizedCopy = name == "copy" &&
                    function.typeParameters.isEmpty() &&
                    parameters.size == resultProperties.size &&
                    parameters.zip(resultProperties).all { (parameter, property) ->
                        property.first !in childScopesByName &&
                                resolvedResultPropertyTypes[property.first]?.let {
                                    checker.sameType(
                                        parameter,
                                        it
                                    )
                                } == true
                    }
            val implementable = when {
                componentProperty != null -> {
                    val provided = if (componentProperty.first in childScopesByName) {
                        childScopesByName.getValue(componentProperty.first).child.resultSupertype
                    } else {
                        resolvedResultPropertyTypes[componentProperty.first]
                    }
                    returnType != null &&
                            (provided?.let(returnType::isAssignableFrom) == true ||
                                    (provided == null && returnType.declaration.isClass(ANY)))
                }

                name == "equals" && function.typeParameters.isEmpty() && parameters.size == 1 ->
                    parameters.single().isMarkedNullable && parameters.single().declaration.isClass(ANY)

                (name == "hashCode" || name == "toString") &&
                        function.typeParameters.isEmpty() && parameters.isEmpty() ->
                    true

                else -> false
            }
            when {
                function.isAbstract && !implementable ->
                    checker.report(
                        spec,
                        "The result supertype ${supertypeDeclaration.simpleName.asString()} declares function $name, which the generated result cannot implement."
                    )

                !function.isAbstract &&
                        (synthesizedCopy || (componentPosition != null && parameters.isEmpty() && !implementable)) ->
                    checker.report(
                        spec,
                        "The result supertype ${supertypeDeclaration.simpleName.asString()} declares function $name, which conflicts with the generated data class member $name."
                    )
            }
        }
    }
    if (!checker.valid) return handled(checker)
    val resultVisibility = restrictiveVisibility(
        listOfNotNull(
            visibility,
            resultSupertype?.visibility,
            *childScopes.map { it.child.resultVisibility }.toTypedArray(),
        )
    )

    // Every generated name is registered in the file context, which backs
    // both the uniqueness of generated names and the aliasing of
    // references that would otherwise be captured.
    val context = FileContext(
        declarationsInPackage(resolver, packageName).mapTo(mutableSetOf()) { it.simpleName.asString() },
    )
    specMemberNames.forEach(context::register)
    context.register(names.builderName)
    context.register(names.resultName)
    if (names.functionName.isNotEmpty()) context.register(names.functionName)
    for (property in listProperties) {
        for (child in property.children) context.register(child.functionName)
    }
    for (property in valueProperties) {
        property.fieldName = context.fileName("${property.name}Field")
    }
    for (property in listProperties) {
        property.fieldName = context.fileName("${property.name}Field")
    }
    for (scope in childScopes) {
        // The scope parameters and the block name are in scope inside the
        // generated child function, so the backing field must not reuse
        // one of them.
        scope.parameters.forEach { context.register(it.name) }
        context.register(scope.blockName)
        val scopeNames = NameAllocator()
        scope.parameters.forEach { scopeNames.newName(it.name) }
        scopeNames.newName(scope.blockName)
        scope.fieldName = context.fileName(scopeNames.newName("${scope.name}Field"))
    }
    for (property in listProperties) {
        for (child in property.children) {
            child.required.forEach { context.register(it.name) }
        }
    }
    GENERATED_NAMES.forEach(context::register)

    val builderTypeName = names.builderType(packageName)
    val resultTypeName = names.resultType(packageName)
    reserveGeneratedNames(
        spec,
        resolver,
        names,
        packageName,
        specQualifiedName,
        requiredProperties,
        listProperties,
        constructorTypes,
        checker,
    )
    if (!checker.valid) return handled(checker)
    val builderType = builderType(
        specTypeName,
        builderTypeName,
        resultTypeName,
        listOf(visibility),
        resultVisibility,
        requiredProperties,
        valueProperties,
        listProperties,
        childScopes,
        context,
    )
    val resultType =
        resultType(resultTypeName, resultVisibility, resultProperties, supertypeTypeName, supertypeOverrides)
    val elementFunctions = elementFunctions(specTypeName, visibility, listProperties, context)
    val buildFunction = if (names.generateFunction) {
        buildFunction(
            names,
            specTypeName,
            resultVisibility,
            builderTypeName,
            resultTypeName,
            requiredProperties,
            context,
        )
    } else {
        null
    }

    val fileSpec = FileSpec.builder(packageName, names.builderName)
        .addFileComment("Generated by dsl-utilities-ksp from %L. Do not edit.", specQualifiedName)
        .addType(builderType)
        .addFunctions(elementFunctions.functions)
        .addProperties(elementFunctions.shorthands)
        .addType(resultType)
        .apply {
            buildFunction?.let(::addFunction)
            context.applyTo(this)
        }
        .build()

    val file = try {
        codeGenerator.createNewFile(
            Dependencies(aggregating = true, *spec.containingFile?.let { arrayOf(it) } ?: emptyArray()),
            packageName,
            names.builderName,
        )
    } catch (_: FileAlreadyExistsException) {
        // Another processor can create the same output path first; report
        // the conflict as a diagnostic instead of letting the build crash.
        val qualifiedName = if (packageName.isEmpty()) names.builderName else "$packageName.${names.builderName}"
        checker.report(spec, "Generated type $qualifiedName is also produced by another declaration.")
        return handled(checker)
    }
    OutputStreamWriter(file, StandardCharsets.UTF_8).use { writer ->
        writer.write(fileSpec.toString())
    }
    return true
}

/**
 * Reports buffered diagnostics and returns `true` when the spec was
 * handled in this round. A spec whose failures are all unresolved
 * types stays unreported so the caller can defer it to a later round.
 */
private fun handled(checker: Checker): Boolean {
    if (checker.onlyUnresolved()) return false
    checker.flush()
    return true
}
