package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isDefault
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Processes interfaces annotated with
 * [DslBuilder][top.ltfan.dslutilities.DslBuilder] and generates a
 * builder class, an immutable result data class, and a top-level
 * build function for each of them. Child scopes declared with
 * [DslChild][top.ltfan.dslutilities.DslChild] and list children declared
 * through [DslList][top.ltfan.dslutilities.DslList] receive generated
 * composition functions.
 */
class DslProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val generatedTypeOwners = mutableMapOf<String, String>()
    private val generatedFunctionOwners = mutableMapOf<String, MutableList<Pair<String, GeneratedSignature>>>()
    private val generatedPropertyOwners = mutableMapOf<String, String>()
    private val reportedStarProjections = mutableSetOf<String>()
    private var packageDeclarations: MutableMap<String, List<KSDeclaration>>? = null

    override fun process(resolver: Resolver): List<KSAnnotated> {
        packageDeclarations = null
        val deferred = mutableListOf<KSAnnotated>()
        for (symbol in resolver.getSymbolsWithAnnotation(DSL_BUILDER_ANNOTATION)) {
            // KSP's validation covers unresolved declarations referenced from
            // the annotated symbol, including types produced by other
            // processors in a later round.
            if (!symbol.validate(enableNewFeatures = true)) {
                deferred += symbol
                continue
            }
            val spec = symbol as? KSClassDeclaration ?: continue
            if (!generate(spec, resolver)) deferred += spec
        }
        return deferred
    }

    /**
     * Generates the code for one spec. Returns `false` when the spec should be
     * deferred to a later round because it only references unresolvable types;
     * diagnostics for genuine errors are reported before returning `true`.
     */
    private fun generate(spec: KSClassDeclaration, resolver: Resolver): Boolean {
        val specName = spec.simpleName.asString()
        val packageName = spec.packageName.asString()
        val annotation = spec.annotation(DSL_BUILDER_ANNOTATION)
        val names = Names.of(specName, annotation)

        if (spec.classKind != ClassKind.INTERFACE) {
            logger.error("@DslBuilder applies to interfaces, but $specName is a ${spec.classKind}.", spec)
            return true
        }
        if (names.resultName == specName || names.builderName == specName) {
            logger.error("The result and builder names of $specName must differ from the interface name.", spec)
            return true
        }
        if (names.resultName == names.builderName) {
            logger.error("The result and builder names of $specName must differ from each other.", spec)
            return true
        }
        if (!names.generateFunction && names.functionName.isNotEmpty()) {
            logger.error("@DslBuilder.functionName of $specName requires generateFunction.", spec)
            return true
        }
        if (!isSimpleIdentifier(names.resultName) || !isSimpleIdentifier(names.builderName)) {
            logger.error("The result and builder names of $specName must be simple identifiers.", spec)
            return true
        }
        if (names.generateFunction && !isSimpleIdentifier(names.functionName)) {
            logger.error("@DslBuilder.functionName of $specName must be a simple identifier.", spec)
            return true
        }

        val checker = Checker(resolver, logger)
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
        if (resultSupertype != null) {
            val supertypeDeclaration = resultSupertype.declaration
            val childScopesByName = childScopes.associateBy { it.name }
            val resultPropertyTypes = resultProperties.toMap()
            for (member in hierarchy.allProperties(supertypeDeclaration)) {
                val property = member.declaration
                val name = property.simpleName.asString()
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
            for (member in hierarchy.abstractFunctions(supertypeDeclaration)) {
                checker.report(
                    spec,
                    "The result supertype ${supertypeDeclaration.simpleName.asString()} declares function ${
                        member.declaration.simpleName.asString()
                    }, which the generated result cannot implement."
                )
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

        val file = codeGenerator.createNewFile(
            Dependencies(aggregating = true, *spec.containingFile?.let { arrayOf(it) } ?: emptyArray()),
            packageName,
            names.builderName,
        )
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

    /**
     * Builds the generated builder class: the required properties arrive
     * as constructor parameters, the mutable members are stored in private
     * fields, and the spec members are overridden with the validation and
     * mapping logic wired inline into the accessors.
     */
    private fun builderType(
        specTypeName: ClassName,
        builderTypeName: ClassName,
        resultTypeName: ClassName,
        visibility: List<KModifier>,
        memberVisibility: KModifier,
        requiredProperties: List<RequiredProperty>,
        valueProperties: List<ValueProperty>,
        listProperties: List<ListProperty>,
        childScopes: List<ChildScope>,
        context: FileContext,
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(builderTypeName)
            .addModifiers(visibility)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .apply {
                        for (property in requiredProperties) {
                            addParameter(ParameterSpec.builder(property.name, property.typeName).build())
                        }
                    }
                    .build()
            )
            .addSuperinterface(specTypeName)

        for (property in requiredProperties) {
            // The same-named property initialized from the constructor
            // parameter emits the parameter as an `override val` of the
            // primary constructor.
            builder.addProperty(
                PropertySpec.builder(property.name, property.typeName, KModifier.OVERRIDE)
                    .initializer("%N", property.name)
                    .build()
            )
        }
        for (property in valueProperties) {
            val initializer = if (property.mapper != null) {
                CodeBlock.of("%L.toStored(%L)", property.mapper.code(context), property.initialValue)
            } else {
                property.initialValue
            }
            builder.addProperty(
                PropertySpec.builder(property.nameField(), property.storageTypeName, KModifier.PRIVATE)
                    .mutable(true)
                    .initializer(initializer)
                    .build()
            )
        }
        for (property in listProperties) {
            builder.addProperty(
                PropertySpec.builder(
                    property.nameField(),
                    MUTABLE_LIST.parameterizedBy(property.elementTypeName),
                    KModifier.PRIVATE,
                )
                    .initializer(CodeBlock.of("%L()", context.member(MUTABLE_LIST_OF)))
                    .build()
            )
        }
        for (property in childScopes) {
            builder.addProperty(
                PropertySpec.builder(
                    property.nameField(),
                    property.child.resultType.asNullable(),
                    KModifier.PRIVATE,
                )
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
        }

        for (property in valueProperties) {
            val getter = FunSpec.getterBuilder()
                .apply {
                    if (property.mapper == null) {
                        addStatement("return %N", property.nameField())
                    } else {
                        addStatement("return %L.toValue(%N)", property.mapper.code(context), property.nameField())
                    }
                }
                .build()
            val setter = FunSpec.setterBuilder()
                .addParameter(VALUE_PARAMETER, property.typeName)
                .apply {
                    if (property.mapper == null) {
                        if (property.validator != null) {
                            addStatement(
                                "%L(%L.validate($VALUE_PARAMETER)) { %S }",
                                context.member(REQUIRE),
                                property.validator.code(context),
                                property.message
                            )
                        }
                        addStatement("%N = $VALUE_PARAMETER", property.nameField())
                    } else {
                        addStatement("val $STORED_VALUE = %L.toStored($VALUE_PARAMETER)", property.mapper.code(context))
                        if (property.validator != null) {
                            addStatement(
                                "%L(%L.validate($STORED_VALUE)) { %S }",
                                context.member(REQUIRE),
                                property.validator.code(context),
                                property.message
                            )
                        }
                        addStatement("%N = $STORED_VALUE", property.nameField())
                    }
                }
                .build()
            builder.addProperty(
                PropertySpec.builder(property.name, property.typeName, KModifier.OVERRIDE)
                    .mutable(true)
                    .getter(getter)
                    .setter(setter)
                    .build()
            )
        }

        for (property in valueProperties) {
            if (property.validator != null) {
                builder.addInitializerBlock(
                    CodeBlock.of(
                        "%L(%L.validate(%N)) { %S }\n",
                        context.member(REQUIRE),
                        property.validator.code(context),
                        property.nameField(),
                        property.message,
                    )
                )
            }
        }

        for (property in listProperties) {
            val setter = FunSpec.setterBuilder()
                .addParameter(VALUE_PARAMETER, property.typeName)
                .addStatement("val $SNAPSHOT = $VALUE_PARAMETER.%L()", context.member(TO_LIST))
                .addStatement("%N.clear()", property.nameField())
                .addStatement("%N.addAll($SNAPSHOT)", property.nameField())
                .build()
            builder.addProperty(
                PropertySpec.builder(
                    property.name,
                    property.typeName,
                    KModifier.OVERRIDE
                )
                    .mutable(true)
                    .getter(FunSpec.getterBuilder().addStatement("return %N", property.nameField()).build())
                    .setter(setter)
                    .build()
            )
        }

        for (property in childScopes) {
            val parameters = property.parameters.map { ParameterSpec.builder(it.name, it.typeName).build() } +
                    ParameterSpec.builder(property.blockName, property.blockTypeName).build()
            val arguments = requiredArguments(property.child.required)
            val scope = context.scope()
            parameters.forEach { scope.register(it.name) }
            val childBuilderName = scope.newName(CHILD_BUILDER_LOCAL)
            builder.addFunction(
                FunSpec.builder(property.name)
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameters(parameters)
                    .addStatement(
                        "val %N = %L(%L)",
                        childBuilderName,
                        context.expression(property.child.builderType),
                        arguments,
                    )
                    .addStatement("%N.invoke(%N)", property.blockName, childBuilderName)
                    .addStatement("%N = %N.$BUILD_FUNCTION()", property.nameField(), childBuilderName)
                    .build()
            )
        }

        val buildCode = CodeBlock.builder()
        for (property in requiredProperties) {
            if (property.validator != null) {
                buildCode.addStatement(
                    "%L(%L.validate(%N)) { %S }",
                    context.member(REQUIRE),
                    property.validator.code(context),
                    property.name,
                    property.message,
                )
            }
        }
        for (property in listProperties) {
            if (property.validator != null) {
                buildCode.addStatement("for ($ELEMENT in %N) {", property.nameField())
                buildCode.addStatement(
                    "%L(%L.validate($ELEMENT)) { %S }",
                    context.member(REQUIRE),
                    property.validator.code(context),
                    property.message,
                )
                buildCode.addStatement("}")
            }
        }
        val arguments = mutableListOf<CodeBlock>()
        fun addArgument(name: String, format: String, vararg values: Any) {
            arguments += CodeBlock.of("%N = $format", name, *values)
        }
        for (property in requiredProperties) addArgument(property.name, "%N", property.name)
        for (property in valueProperties) addArgument(property.name, "%N", property.name)
        for (property in listProperties) {
            addArgument(property.name, "%N.%L()", property.nameField(), context.member(TO_LIST))
        }
        for (property in childScopes) {
            addArgument(
                property.name,
                "%L(%N) { %S }",
                context.member(REQUIRE_NOT_NULL),
                property.nameField(),
                "Property ${property.name} is required.",
            )
        }
        builder.addFunction(
            FunSpec.builder(BUILD_FUNCTION)
                .addModifiers(memberVisibility)
                .returns(resultTypeName)
                .addCode(buildCode.build())
                .addStatement("return %T(%L)", resultTypeName, arguments.joinToCode(", "))
                .build()
        )
        return builder.build()
    }

    /**
     * Builds the immutable result class. Matching properties of the result
     * supertype are overridden; the constructor properties back the data class
     * semantics of equality, copy and component functions.
     */
    private fun resultType(
        resultTypeName: ClassName,
        visibility: KModifier,
        resultProperties: List<Pair<String, TypeName>>,
        supertype: TypeName?,
        overrides: Set<String>,
    ): TypeSpec {
        val classModifiers = if (resultProperties.isEmpty()) listOf() else listOf(KModifier.DATA)
        val builder = TypeSpec.classBuilder(resultTypeName)
            .addModifiers(visibility, *classModifiers.toTypedArray())
        if (resultProperties.isNotEmpty()) {
            builder.primaryConstructor(
                FunSpec.constructorBuilder()
                    .apply {
                        for ((name, typeName) in resultProperties) {
                            addParameter(ParameterSpec.builder(name, typeName).build())
                        }
                    }
                    .build()
            )
        }
        for ((name, typeName) in resultProperties) {
            val modifiers = mutableListOf(KModifier.PUBLIC)
            if (name in overrides) modifiers += KModifier.OVERRIDE
            builder.addProperty(
                PropertySpec.builder(name, typeName, *modifiers.toTypedArray())
                    .initializer("%N", name)
                    .build()
            )
        }
        if (supertype != null) builder.addSuperinterface(supertype)
        return builder.build()
    }

    /**
     * Builds the element functions of the declared list children together with
     * the property shorthands of children without required properties.
     */
    private fun elementFunctions(
        specTypeName: ClassName,
        parentVisibility: KModifier,
        listProperties: List<ListProperty>,
        context: FileContext,
    ): ElementFunctions {
        val functions = mutableListOf<FunSpec>()
        val shorthands = mutableListOf<PropertySpec>()
        for (property in listProperties) {
            for (child in property.children) {
                val scope = context.scope()
                child.required.forEach { scope.register(it.name) }
                val blockName = scope.newName(BLOCK_PARAMETER)
                val childBuilderName = scope.newName(CHILD_BUILDER_LOCAL)
                val arguments = requiredArguments(child.required)
                val visibility = restrictiveVisibility(
                    listOf(parentVisibility, child.builderVisibility, child.resultVisibility)
                )
                functions += FunSpec.builder(child.functionName)
                    .addModifiers(visibility, KModifier.INLINE)
                    .receiver(specTypeName)
                    .apply {
                        for (required in child.required) addParameter(requiredParameter(required))
                    }
                    .addParameter(
                        ParameterSpec.builder(
                            blockName,
                            LambdaTypeName.get(
                                receiver = child.specTypeName,
                                returnType = UNIT,
                            ),
                        )
                            .apply {
                                if (!child.requiresConfiguration) defaultValue("{}")
                            }
                            .build()
                    )
                    .addStatement(
                        "val %N = %L(%L)",
                        childBuilderName,
                        context.expression(child.builderType),
                        arguments,
                    )
                    .addStatement("%N.invoke(%N)", blockName, childBuilderName)
                    .addStatement("this.%N.add(%N.$BUILD_FUNCTION())", property.name, childBuilderName)
                    .build()
                if (child.required.isEmpty() && !child.requiresConfiguration) {
                    shorthands += PropertySpec.builder(child.functionName, UNIT)
                        .addModifiers(visibility)
                        .receiver(specTypeName)
                        .getter(
                            FunSpec.getterBuilder()
                                .addModifiers(KModifier.INLINE)
                                .addStatement("%N()", child.functionName)
                                .build()
                        )
                        .build()
                }
            }
        }
        return ElementFunctions(functions, shorthands)
    }

    /**
     * Builds the top-level entry point that creates the builder, runs the DSL
     * block and returns the built result.
     */
    private fun buildFunction(
        names: Names,
        specTypeName: ClassName,
        visibility: KModifier,
        builderTypeName: ClassName,
        resultTypeName: ClassName,
        requiredProperties: List<RequiredProperty>,
        context: FileContext,
    ): FunSpec {
        val scope = context.scope()
        requiredProperties.forEach { scope.register(it.name) }
        val blockName = scope.newName(BLOCK_PARAMETER)
        val builderName = scope.newName(BUILDER_LOCAL)
        val arguments = requiredArguments(requiredProperties)
        return FunSpec.builder(names.functionName)
            .addModifiers(visibility, KModifier.INLINE)
            .apply {
                for (property in requiredProperties) {
                    addParameter(requiredParameter(property))
                }
            }
            .addParameter(
                ParameterSpec.builder(
                    blockName,
                    LambdaTypeName.get(receiver = specTypeName, returnType = UNIT),
                )
                    .defaultValue("{}")
                    .build()
            )
            .returns(resultTypeName)
            .addStatement("val %N = %T(%L)", builderName, builderTypeName, arguments)
            .addStatement("%N.invoke(%N)", blockName, builderName)
            .addStatement("return %N.$BUILD_FUNCTION()", builderName)
            .build()
    }

    /**
     * Builds the parameter of a required property for the generated
     * inline entry points. A function-typed parameter cannot be passed
     * to the non-inline builder call from an inline function, so it is
     * `noinline`; a nullable function type requires that modifier as well.
     */
    private fun requiredParameter(property: RequiredProperty): ParameterSpec =
        ParameterSpec.builder(property.name, property.typeName)
            .apply {
                if (property.type.isFunctionType || property.type.isSuspendFunctionType) {
                    addModifiers(KModifier.NOINLINE)
                }
            }
            .build()

    /**
     * Builds escaped `name = name` constructor arguments for required
     * properties.
     */
    private fun requiredArguments(properties: List<RequiredProperty>): CodeBlock =
        properties.map { CodeBlock.of("%N = %N", it.name, it.name) }.joinToCode(", ")

    private fun ValueProperty.nameField(): String = fieldName

    private fun ListProperty.nameField(): String = fieldName

    private fun ChildScope.nameField(): String = fieldName

    /**
     * Returns whether [declaration] or one of its supertypes declares an
     * abstract property named [name].
     */
    private fun requiresProperty(declaration: KSClassDeclaration, name: String): Boolean =
        (sequenceOf(declaration.asStarProjectedType()) + declaration.getAllSuperTypes())
            .mapNotNull { it.declaration as? KSClassDeclaration }
            .distinct()
            .any { current ->
                current.getDeclaredProperties().any { it.isAbstract() && it.simpleName.asString() == name }
            }

    /**
     * Copies [TypeName] through its base declaration, keeping defaults for the
     * other arguments.
     */
    private fun TypeName.asNullable(): TypeName = copy(nullable = true)

    /**
     * Required properties are declared as `val`: they are parameters of the
     * generated builder and of the generated build function, so the compiler
     * enforces their presence.
     */
    private fun requiredProperty(
        name: String,
        property: KSPropertyDeclaration,
        type: KSType,
        declarationType: KSType,
        annotation: KSAnnotation?,
        checker: Checker,
    ): RequiredProperty? {
        val diagnostics = checker.diagnosticCount
        val resolvedType = checker.expandAliases(type)
        val typeName = checker.renderTypeName(property, type, declarationType)
            ?: return null
        if (annotation?.providedString("initial") != null) {
            checker.report(property, "@DslValue.initial applies to var properties; $name is a val.")
            return null
        }
        val mapperType = annotation?.type("mapper")
        if (mapperType != null && !mapperType.isUnit()) {
            checker.report(property, "@DslValue.mapper applies to var properties; $name is a val.")
            return null
        }
        val validator = checker.validator(property, name, annotation?.type("validator"), resolvedType)
        if (checker.diagnosticCount != diagnostics) return null
        return RequiredProperty(name, typeName, resolvedType, validator, checker.message(annotation, name))
    }

    /**
     * Value properties are declared as `var` with
     * [DslValue][top.ltfan.dslutilities.DslValue]: they are set inside the
     * DSL block with the validator and mapper wired inline into the generated
     * accessors.
     */
    private fun valueProperty(
        name: String,
        property: KSPropertyDeclaration,
        type: KSType,
        declarationType: KSType,
        annotation: KSAnnotation,
        checker: Checker,
    ): ValueProperty? {
        val diagnostics = checker.diagnosticCount
        val resolvedType = checker.expandAliases(type)
        if (resolvedType.declaration.isClass(MUTABLE_LIST)) {
            checker.report(
                property,
                "Property $name has a MutableList type; list properties are declared with @DslList."
            )
            return null
        }
        val typeName = checker.renderTypeName(property, type, declarationType) ?: return null
        val mapper = checker.mapper(property, name, annotation.type("mapper"), resolvedType)
        if (checker.diagnosticCount != diagnostics) return null
        val initial = annotation.providedString("initial")
        when (initial) {
            null if !resolvedType.isMarkedNullable -> {
                checker.report(
                    property,
                    "Property $name is non-nullable and has no initial value; declare it as a val to make it required, declare it with a nullable type, or provide @DslValue.initial."
                )
                return null
            }

            null if mapper != null -> {
                checker.report(
                    property,
                    "Property $name is nullable and has a mapper; provide @DslValue.initial so the stored value is always present."
                )
                return null
            }
        }
        val initialValue = if (initial == null) {
            CodeBlock.of("null")
        } else {
            checker.literal(property, name, initial, resolvedType) ?: return null
        }
        val validator = checker.validator(
            property,
            name,
            annotation.type("validator"),
            mapper?.storedType ?: resolvedType,
        )
        if (checker.diagnosticCount != diagnostics) return null
        return ValueProperty(
            name = name,
            typeName = typeName,
            type = resolvedType,
            storageTypeName = mapper?.storageTypeName ?: typeName,
            initialValue = initialValue,
            mapper = mapper?.target,
            validator = validator,
            message = checker.message(annotation, name),
        )
    }

    /**
     * List properties are declared as `var` with
     * [DslList][top.ltfan.dslutilities.DslList]: their elements are mutated
     * inside the DSL block and are validated in the generated build function;
     * the generated result class exposes them as read-only lists. Each
     * declared child receives a generated element function that builds the
     * child value and adds it to the list.
     */
    private fun listProperty(
        name: String,
        property: KSPropertyDeclaration,
        type: KSType,
        declarationType: KSType,
        annotation: KSAnnotation,
        checker: Checker,
    ): ListProperty? {
        val diagnostics = checker.diagnosticCount
        if (!property.isMutable) {
            checker.report(property, "@DslList applies to var properties; $name is a val.")
            return null
        }
        val resolvedType = checker.expandAliases(type)
        if (!resolvedType.declaration.isClass(MUTABLE_LIST) ||
            resolvedType.arguments.size != 1
        ) {
            checker.report(
                property,
                "Property $name annotated with @DslList has a type other than MutableList of the element type."
            )
            return null
        }
        if (resolvedType.isMarkedNullable) {
            checker.report(
                property,
                "@DslList property $name must not be nullable; the generated list is always present."
            )
            return null
        }
        val argument = resolvedType.arguments.single()
        if (argument.variance != Variance.INVARIANT) {
            checker.report(
                property,
                "@DslList property $name must not use a use-site projection; declare MutableList of the element type."
            )
            return null
        }
        val elementType = argument.type?.resolve() ?: run {
            checker.report(property, "Property $name has an unsupported element type.")
            return null
        }
        val declarationElementType = checker.expandAliases(declarationType).arguments.getOrNull(0)?.type?.resolve()
        val elementTypeName = checker.renderTypeName(property, elementType, declarationElementType) ?: return null
        val listTypeName = checker.renderTypeName(property, type, declarationType) ?: return null
        val resolvedElementType = checker.expandAliases(elementType)
        val validator = checker.validator(property, name, annotation.type("validator"), resolvedElementType)
        if (checker.diagnosticCount != diagnostics) return null

        val children = mutableListOf<ChildSpec>()
        for (argumentType in annotation.typeArray("children")) {
            if (argumentType.isError) {
                checker.reportUnresolved(property, "A child of @DslList property $name is not resolvable yet.")
                return null
            }
            val childDeclaration = checker.expandAliases(argumentType).declaration as? KSClassDeclaration ?: run {
                checker.report(property, "The children of @DslList property $name must be DslBuilder interfaces.")
                return null
            }
            val child = resolveChild(childDeclaration, checker) ?: return null
            val elementQualifiedName = resolvedElementType.makeNotNullable().declaration.qualifiedName?.asString()
            val acceptsResult = elementQualifiedName == ANY.canonicalName ||
                    elementQualifiedName == child.resultType.canonicalName ||
                    child.resultSupertype?.let(resolvedElementType::isAssignableFrom) == true
            if (!acceptsResult) {
                checker.report(
                    property,
                    "Child ${child.specTypeName.canonicalName} produces ${child.resultType.canonicalName}, which is not assignable to the element type $elementTypeName of @DslList property $name."
                )
                return null
            }
            children += child
        }
        if (!checker.valid) return null
        val functionNames = children.map { it.functionName }
        if (functionNames.size != functionNames.toSet().size) {
            checker.report(property, "Two or more children of @DslList property $name produce the same function name.")
            return null
        }
        return ListProperty(
            name,
            listTypeName,
            elementTypeName,
            resolvedElementType,
            validator,
            checker.message(annotation, name),
            children,
        )
    }

    /**
     * Child scopes are declared as `@DslChild` functions whose last parameter
     * is a function type with the child DslBuilder interface as receiver and
     * whose other parameters carry the required properties of the child; the
     * processor generates the function body.
     */
    private fun childScope(
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
    private fun resolveChild(declaration: KSClassDeclaration, checker: Checker): ChildSpec? {
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
        if (resultVisibility == KModifier.INTERNAL && declaration.containingFile == null) {
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
    private fun resolveResultSupertype(
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
            val sameModule = declaration.containingFile != null
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

    private fun restrictiveVisibility(visibilities: Iterable<KModifier>): KModifier =
        if (KModifier.INTERNAL in visibilities) KModifier.INTERNAL else KModifier.PUBLIC

    /**
     * Reports generated declaration collisions before opening an output file,
     * keeping invalid custom or derived names as KSP diagnostics instead of
     * file-creation failures or later redeclaration errors. Declarations
     * already present in the specification's package are reserved as well,
     * because generated functions and extension properties share the
     * package-level scope with them.
     */
    private fun reserveGeneratedNames(
        spec: KSClassDeclaration,
        resolver: Resolver,
        names: Names,
        packageName: String,
        specQualifiedName: String,
        requiredProperties: List<RequiredProperty>,
        listProperties: List<ListProperty>,
        checker: Checker,
    ) {
        fun qualified(name: String): String = if (packageName.isEmpty()) name else "$packageName.$name"
        val owner = spec.qualifiedName?.asString() ?: spec.simpleName.asString()
        val typeNames = listOf(names.builderName, names.resultName)
        for (name in typeNames) {
            val qualifiedName = qualified(name)
            val existing = resolver.getClassDeclarationByName(resolver.getKSNameFromString(qualifiedName)) != null ||
                    declarationsInPackage(resolver, packageName).any { declaration ->
                        declaration is KSTypeAlias && declaration.simpleName.asString() == name
                    }
            val reservedBy = generatedTypeOwners[qualifiedName]
            when {
                existing ->
                    checker.report(spec, "Generated type $qualifiedName conflicts with an existing declaration.")

                reservedBy != null && reservedBy != owner ->
                    checker.report(spec, "Generated type $qualifiedName is also produced by $reservedBy.")
            }
        }

        val reservedFunctions = mutableListOf<Pair<String, Pair<String, GeneratedSignature>>>()
        fun reserveFunction(name: String, signature: GeneratedSignature) {
            val qualifiedName = qualified(name)
            val existing = resolver.getFunctionDeclarationsByName(
                resolver.getKSNameFromString(qualifiedName),
                includeTopLevel = true,
            ).any { function ->
                function.parentDeclaration == null &&
                        function.containingFile != null &&
                        function.packageName.asString() == packageName &&
                        matchesSignature(function, signature, checker, resolver)
            }
            val reservedBy = generatedFunctionOwners[qualifiedName]
                ?.firstOrNull { (_, reserved) -> sameSignature(reserved, signature, checker) }
                ?.first
            when {
                existing ->
                    checker.report(spec, "Generated function $qualifiedName conflicts with an existing declaration.")

                reservedBy != null && reservedBy != owner ->
                    checker.report(spec, "Generated function $qualifiedName is also produced by $reservedBy.")
            }
            reservedFunctions += qualifiedName to (owner to signature)
        }

        val specType = spec.asType(emptyList())
        if (names.generateFunction) {
            reserveFunction(names.functionName, GeneratedSignature(null, requiredProperties.map { it.type }, specType))
        }
        for (property in listProperties) {
            for (child in property.children) {
                reserveFunction(
                    child.functionName,
                    GeneratedSignature(specType, child.required.map { it.type }, child.specType),
                )
                if (child.required.isEmpty() && !child.requiresConfiguration) {
                    val shorthandKey = "$specQualifiedName.${child.functionName}"
                    val existing = declarationsInPackage(resolver, packageName).any { declaration ->
                        declaration is KSPropertyDeclaration &&
                                declaration.parentDeclaration == null &&
                                declaration.containingFile != null &&
                                declaration.simpleName.asString() == child.functionName &&
                                declaration.extensionReceiver?.resolve()
                                    ?.let { checker.erasureKey(it) } == specQualifiedName
                    }
                    val reservedBy = generatedPropertyOwners[shorthandKey]
                    when {
                        existing ->
                            checker.report(
                                spec,
                                "Generated extension property ${child.functionName} conflicts with an existing declaration."
                            )

                        reservedBy != null && reservedBy != owner ->
                            checker.report(
                                spec,
                                "Generated extension property ${child.functionName} is also produced by $reservedBy."
                            )
                    }
                    if (checker.valid) generatedPropertyOwners[shorthandKey] = owner
                }
            }
        }

        if (!checker.valid) return
        for (name in typeNames) generatedTypeOwners[qualified(name)] = owner
        for ((qualifiedName, entry) in reservedFunctions) {
            generatedFunctionOwners.getOrPut(qualifiedName) { mutableListOf() } += entry
        }
    }

    /**
     * Returns `true` when two generated signatures would be conflicting
     * overloads, which is the case when their receivers and parameter types
     * denote the same Kotlin types.
     */
    private fun sameSignature(
        first: GeneratedSignature,
        second: GeneratedSignature,
        checker: Checker,
    ): Boolean =
        checker.sameType(first.receiver, second.receiver) &&
                first.parameters.size == second.parameters.size &&
                first.parameters.zip(second.parameters).all { (firstType, secondType) ->
                    checker.sameType(firstType, secondType)
                } &&
                checker.sameType(first.blockReceiver, second.blockReceiver)

    /**
     * Returns `true` when a supertype property of type [expectedType] can be
     * overridden by the generated concrete result of [child]. The result class
     * is generated in the same round, so its declared result supertype and its
     * qualified name stand in for the class itself; the result is a non-null
     * class, so `Any` accepts it.
     */
    private fun acceptsChildResult(expectedType: KSType, child: ChildSpec, checker: Checker): Boolean {
        val resolvedExpected = checker.expandAliases(expectedType)
        val expectedName = resolvedExpected.makeNotNullable().declaration.qualifiedName?.asString()
        return expectedName == ANY.canonicalName ||
                expectedName == child.resultType.canonicalName ||
                child.resultSupertype?.let { resolvedExpected.isAssignableFrom(it) } == true
    }

    /**
     * Returns `true` when [function] declares the same Kotlin signature
     * as a generated [signature], so emitting the generated declaration
     * would produce conflicting overloads. Kotlin compares names and
     * parameter types; an extension receiver counts as the first parameter.
     */
    private fun matchesSignature(
        function: KSFunctionDeclaration,
        signature: GeneratedSignature,
        checker: Checker,
        resolver: Resolver,
    ): Boolean {
        if (!checker.sameType(function.extensionReceiver?.resolve(), signature.receiver)) return false
        if (function.parameters.size != signature.parameters.size + 1) return false
        for ((index, parameter) in signature.parameters.withIndex()) {
            if (!checker.sameType(function.parameters[index].type.resolve(), parameter)) return false
        }
        val rawBlock = function.parameters.last().type.resolve()
        val blockShape = checker.aliasTarget(rawBlock)
        if (blockShape.isMarkedNullable) return false
        if (!blockShape.isFunctionType || blockShape.isSuspendFunctionType) return false
        if (blockShape.annotations.none { it.isMarker(EXTENSION_FUNCTION_TYPE) }) return false
        val blockArguments = checker.expandAliases(rawBlock).arguments
        if (blockArguments.size != 2) return false
        val blockReceiver = blockArguments[0].type?.resolve() ?: return false
        val blockReturnType = blockArguments[1].type?.resolve() ?: return false
        return checker.sameType(blockReceiver, signature.blockReceiver) &&
                checker.sameType(blockReturnType, resolver.builtIns.unitType)
    }

    /**
     * Returns the top-level declarations of [packageName], cached for the
     * current processing round so that each specification reuses one lookup.
     */
    @OptIn(KspExperimental::class)
    private fun declarationsInPackage(resolver: Resolver, packageName: String): List<KSDeclaration> {
        val cache = packageDeclarations ?: mutableMapOf<String, List<KSDeclaration>>().also {
            packageDeclarations = it
        }
        return cache.getOrPut(packageName) { resolver.getDeclarationsFromPackage(packageName).toList() }
    }

    /**
     * Returns the visibility the generated declarations need to reference the
     * specification from the generated top-level file: the most restrictive
     * visibility found along the declaration chain, or `null` after
     * reporting when no generated declaration can reference the spec at all.
     */
    private fun effectiveVisibility(spec: KSDeclaration, checker: Checker): KModifier? {
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

    /**
     * A member of a DslBuilder interface together with the type-parameter
     * environment of the class declaring it, so types declared against
     * generic bases resolve from the perspective of the analyzed class.
     */
    private class SubstitutedMember<T : KSDeclaration>(
        val declaration: T,
        val environment: Map<KSTypeParameter, KSType>,
    )

    /**
     * Walks the supertype hierarchy of a DslBuilder interface and collects the
     * abstract members the generated declarations must override. The nearest
     * declaration wins when a name is redeclared along the hierarchy; an
     * unresolvable supertype defers the spec to a later round.
     */
    private class Hierarchy(
        private val checker: Checker,
        private val reportedStarProjections: MutableSet<String>,
    ) {

        fun abstractFunctions(declaration: KSClassDeclaration): List<SubstitutedMember<KSFunctionDeclaration>> {
            val members = LinkedHashMap<String, SubstitutedMember<KSFunctionDeclaration>>()
            val seen = mutableSetOf<String>()
            walk(declaration, emptyMap()) { member, environment, _ ->
                if (member !is KSFunctionDeclaration) return@walk
                val signature = checker.functionSignature(member, environment)
                if (!seen.add(signature)) return@walk
                if (member.isAbstract) members[signature] = SubstitutedMember(member, environment)
            }
            return members.values.toList()
        }

        /**
         * Collects every function of the hierarchy, abstract or concrete, so the
         * caller can diagnose annotations placed on functions that declare their
         * own body.
         */
        fun allFunctions(declaration: KSClassDeclaration): List<SubstitutedMember<KSFunctionDeclaration>> {
            val members = LinkedHashMap<String, SubstitutedMember<KSFunctionDeclaration>>()
            val seen = mutableSetOf<String>()
            walk(declaration, emptyMap()) { member, environment, _ ->
                if (member !is KSFunctionDeclaration) return@walk
                val signature = checker.functionSignature(member, environment)
                if (!seen.add(signature)) return@walk
                members[signature] = SubstitutedMember(member, environment)
            }
            return members.values.toList()
        }

        /**
         * Collects declared function names across the hierarchy for
         * extension-collision checks.
         */
        fun functionNames(declaration: KSClassDeclaration): Set<String> = buildSet {
            walk(declaration, emptyMap()) { member, _, _ ->
                if (member is KSFunctionDeclaration) add(member.simpleName.asString())
            }
        }

        /**
         * Collects every property of the hierarchy, abstract or concrete, so the
         * caller can tell override candidates from members that must be provided.
         * A declaration at the nearest depth wins; compatible siblings at the same
         * depth are merged by override specificity.
         */
        fun allProperties(
            declaration: KSClassDeclaration,
            initialEnvironment: Map<KSTypeParameter, KSType> = emptyMap(),
        ): List<SubstitutedMember<KSPropertyDeclaration>> {
            val candidates = linkedMapOf<String, MutableList<PropertyCandidate>>()
            walk(declaration, initialEnvironment) { member, environment, depth ->
                if (member !is KSPropertyDeclaration) return@walk
                val name = member.simpleName.asString()
                candidates.getOrPut(name, ::mutableListOf) += PropertyCandidate(
                    SubstitutedMember(member, environment),
                    depth,
                )
            }
            return candidates.mapNotNull { (name, declarations) ->
                selectProperty(declaration, name, declarations)
            }
        }

        private fun selectProperty(
            root: KSClassDeclaration,
            name: String,
            candidates: List<PropertyCandidate>,
        ): SubstitutedMember<KSPropertyDeclaration>? {
            val nearestDepth = candidates.minOfOrNull { it.depth } ?: return null
            val nearest = candidates.filter { it.depth == nearestDepth }.map { it.member }
            if (nearest.size == 1) return nearest.single()

            val types = nearest.associateWith { member ->
                checker.expandAliases(checker.substituteType(member.declaration.type.resolve(), member.environment))
            }
            if (types.values.any { it.isError }) {
                checker.reportUnresolved(root, "An inherited type of property $name is not resolvable yet.")
                return nearest.first()
            }
            fun overrides(
                candidate: SubstitutedMember<KSPropertyDeclaration>,
                other: SubstitutedMember<KSPropertyDeclaration>,
            ): Boolean {
                if (candidate === other) return true
                val candidateOwnerIsNarrower = checker.ownerIsSubtypeOf(candidate.declaration, other.declaration, root)
                val otherOwnerIsNarrower = checker.ownerIsSubtypeOf(other.declaration, candidate.declaration, root)
                if (candidateOwnerIsNarrower != otherOwnerIsNarrower) return candidateOwnerIsNarrower
                val candidateType = types.getValue(candidate)
                val otherType = types.getValue(other)
                return if (other.declaration.isMutable) {
                    candidate.declaration.isMutable &&
                            checker.accepts(otherType, candidateType, root) &&
                            checker.accepts(candidateType, otherType, root)
                } else {
                    checker.accepts(otherType, candidateType, root)
                }
            }

            val dominant = nearest.filter { candidate -> nearest.all { overrides(candidate, it) } }
            if (dominant.size == 1) return dominant.single()
            val concrete = dominant.filterNot { it.declaration.isAbstractMember() }
            if (concrete.size == 1) return concrete.single()

            val renderedTypes = nearest.mapNotNull { checker.typeNameOrNull(types.getValue(it)) }
                .distinct()
                .joinToString()
            checker.report(
                root,
                "Inherited property $name has no unique most-specific declaration" +
                        renderedTypes.takeIf { it.isNotEmpty() }?.let { " among types $it" }.orEmpty() +
                        "; redeclare it in ${root.simpleName.asString()}."
            )
            return nearest.first()
        }

        /** Returns `true` when [property] is abstract in its declaring interface. */
        fun isAbstract(property: KSPropertyDeclaration): Boolean = property.isAbstractMember()

        /**
         * Returns `true` when the property is abstract in its declaring interface,
         * so generated code must override it. KSP synthesizes accessors for
         * properties without declared ones, and synthesized accessors of abstract
         * properties carry the abstract modifier; an accessor with a body or a
         * backing field makes the property concrete.
         */
        private fun KSPropertyDeclaration.isAbstractMember(): Boolean {
            if (hasBackingField) return false
            getter?.let { if (Modifier.ABSTRACT !in it.modifiers) return false }
            setter?.let { if (Modifier.ABSTRACT !in it.modifiers) return false }
            return true
        }

        private fun walk(
            declaration: KSClassDeclaration,
            initialEnvironment: Map<KSTypeParameter, KSType>,
            visit: (KSDeclaration, Map<KSTypeParameter, KSType>, Int) -> Unit,
        ) {
            val pending = ArrayDeque<HierarchyEntry>()
            pending += HierarchyEntry(declaration, initialEnvironment, 0)
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val (current, environment, depth) = pending.removeFirst()
                val name = current.qualifiedName?.asString() ?: continue
                if (!visited.add(name)) continue
                for (member in current.declarations) visit(member, environment, depth)
                for (superType in current.superTypes) {
                    val resolved = checker.expandAliases(superType.resolve())
                    if (resolved.isError) {
                        checker.reportUnresolved(
                            declaration,
                            "A supertype of the DslBuilder interface ${declaration.simpleName.asString()} is not resolvable yet."
                        )
                        return
                    }
                    val superDeclaration = resolved.declaration as? KSClassDeclaration ?: continue
                    if (resolved.hasUnboundProjection()) {
                        val key =
                            "${declaration.qualifiedName?.asString()}:${superDeclaration.qualifiedName?.asString()}"
                        if (reportedStarProjections.add(key)) {
                            checker.report(
                                declaration,
                                "Star-projected supertype ${superDeclaration.simpleName.asString()} is not supported by DslBuilder inheritance."
                            )
                        }
                        continue
                    }
                    val nextEnvironment = superDeclaration.typeParameters.zip(resolved.arguments)
                        .associate { (parameter, argument) ->
                            val argumentType = requireNotNull(argument.type).resolve()
                            parameter to checker.substituteType(argumentType, environment)
                        }
                    pending += HierarchyEntry(superDeclaration, nextEnvironment, depth + 1)
                }
            }
        }

        private class PropertyCandidate(
            val member: SubstitutedMember<KSPropertyDeclaration>,
            val depth: Int,
        )

        private data class HierarchyEntry(
            val declaration: KSClassDeclaration,
            val environment: Map<KSTypeParameter, KSType>,
            val depth: Int,
        )
    }

    /**
     * Collects compile-time diagnostics and prepares KSP types for the
     * generated code. Diagnostics are buffered: the caller reports them at
     * the end of generation, or defers the spec to a later round when every
     * diagnostic was an unresolved type. Type rendering is delegated to
     * KotlinPoet, so nullability, arguments, annotations and function types
     * follow the library's handling instead of hand-written string building.
     */
    private class Checker(private val resolver: Resolver, private val logger: KSPLogger) {
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

                    renderByName ->
                        classifierTypeName(symbol, aliasUsage, aliasUsage, null)
                            .annotated(annotationSources, ignoreExtensionMarker = isFunction)

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
            if (receiver is LambdaTypeName && receiver.annotations.isEmpty()) {
                // KotlinPoet only parenthesizes an annotated lambda receiver.
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
         * repeats it; compiler markers and annotations that are expressed through
         * the type syntax read as absent.
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
         * newline in property initializers.
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

    private class ElementFunctions(
        val functions: List<FunSpec>,
        val shorthands: List<PropertySpec>,
    )

    /**
     * A class or object reference used as an expression: an object is
     * referenced by name, a class through its no-argument constructor.
     */
    private class Instantiation(
        val type: ClassName,
        val construct: Boolean,
    ) {
        fun code(context: FileContext): CodeBlock = context.expression(type, construct)
    }

    /**
     * The naming state of one generated file: every simple name that the file
     * declares and the aliased imports that references need because of them.
     * Names are registered where they are created, so the registry stays in
     * sync with the generated declarations.
     */
    private class FileContext(
        private val packageNames: Set<String>,
    ) {
        private val names = mutableSetOf<String>()
        private val fileAllocator = NameAllocator()
        private val aliases = mutableListOf<AliasRequest>()
        private val aliasNames = mutableMapOf<String, String>()
        private val aliasAllocator = NameAllocator().apply {
            packageNames.forEach { newName(it) }
        }

        /** Registers a name that the generated file already declares. */
        fun register(name: String) {
            if (names.add(name)) {
                fileAllocator.newName(name)
                aliasAllocator.newName(name)
            }
        }

        /** Allocates a file-level name, such as a backing field. */
        fun fileName(preferred: String): String =
            fileAllocator.newName(preferred).also { registerAllocated(it) }

        fun isShadowed(name: String): Boolean = name in names

        /** Returns the scope of the locals of one generated function body. */
        fun scope(): LocalScope = LocalScope()

        /**
         * Renders a reference to a top-level function or property. The explicit
         * import that KotlinPoet adds keeps the reference bound to the declaration
         * even when the package declares one of the same name; a name that is in
         * scope in the generated file receives an alias.
         */
        fun member(member: MemberName): CodeBlock {
            val code = CodeBlock.of("%M", member)
            if (isShadowed(member.simpleName)) aliasOfMember(member)
            return code
        }

        /**
         * Renders a reference to [type] as an expression. The name stays with
         * KotlinPoet, which escapes it and manages its import; a name that is in
         * scope in the generated file receives an alias instead. Nested types
         * are matched through their top-level class name, which is the name the
         * rendered reference starts with.
         */
        fun expression(type: ClassName, construct: Boolean = false): CodeBlock {
            val code = if (construct) CodeBlock.of("%T()", type) else CodeBlock.of("%T", type)
            val topLevelName = type.topLevelClassName().simpleName
            if (!isShadowed(type.simpleName) && !isShadowed(topLevelName)) return code
            aliasOfType(type)
            return code
        }

        fun applyTo(builder: FileSpec.Builder) {
            aliases.forEach { it.applyTo(builder) }
        }

        /** Allocates and registers the locals of one generated function body. */
        inner class LocalScope {
            private val allocator = NameAllocator()

            /**
             * Reserves a name that the generated function body declares as a
             * user-written parameter. The name is kept as written; the scope allocator
             * only learns it, so generated locals avoid it.
             */
            fun register(name: String) {
                allocator.newName(name)
                registerAllocated(name)
            }

            fun newName(suggestion: String): String =
                allocator.newName(suggestion).also { registerAllocated(it) }
        }

        private fun registerAllocated(name: String) {
            if (names.add(name)) aliasAllocator.newName(name)
        }

        private fun aliasOfType(type: ClassName): String =
            aliasNames.getOrPut(type.canonicalName) {
                aliasAllocator.newName("${type.simpleName}Ref")
                    .also {
                        aliases += AliasRequest.Type(type, it)
                        // The alias is a name of this file, so a reference that
                        // reuses it receives its own alias.
                        names += it
                    }
            }

        private fun aliasOfMember(member: MemberName): String =
            aliasNames.getOrPut(member.canonicalName) {
                aliasAllocator.newName("stdlib${member.simpleName.replaceFirstChar { it.uppercase() }}")
                    .also {
                        aliases += AliasRequest.Member(member, it)
                        names += it
                    }
            }

        private sealed interface AliasRequest {
            val alias: String

            fun applyTo(builder: FileSpec.Builder)

            data class Type(val type: ClassName, override val alias: String) : AliasRequest {
                override fun applyTo(builder: FileSpec.Builder) {
                    builder.addAliasedImport(type, alias)
                }
            }

            data class Member(val member: MemberName, override val alias: String) : AliasRequest {
                override fun applyTo(builder: FileSpec.Builder) {
                    builder.addAliasedImport(member, alias)
                }
            }
        }
    }

    private data class MapperInfo(
        val target: Instantiation,
        val storageTypeName: TypeName,
        val storedType: KSType,
    )

    private class RequiredProperty(
        val name: String,
        val typeName: TypeName,
        val type: KSType,
        val validator: Instantiation?,
        val message: String,
    )

    private class Parameter(
        val name: String,
        val typeName: TypeName,
    )

    private class ValueProperty(
        val name: String,
        val typeName: TypeName,
        val type: KSType,
        val storageTypeName: TypeName,
        val initialValue: CodeBlock,
        val mapper: Instantiation?,
        val validator: Instantiation?,
        val message: String,
    ) {
        var fieldName: String = "${name}Field"
    }

    private class ListProperty(
        val name: String,
        val typeName: TypeName,
        val elementTypeName: TypeName,
        val elementType: KSType,
        val validator: Instantiation?,
        val message: String,
        val children: List<ChildSpec>,
    ) {
        var fieldName: String = "${name}Field"
    }

    /**
     * The Kotlin signature of a generated top-level function: its extension
     * receiver, the required value parameters, and the trailing child block.
     */
    private class GeneratedSignature(
        val receiver: KSType?,
        val parameters: List<KSType>,
        val blockReceiver: KSType,
    )

    /**
     * A DslBuilder interface used as a child: its generated builder and
     * result classes are referenced as [ClassName] values so that the parent
     * generated code can construct and build it.
     */
    private class ChildSpec(
        val functionName: String,
        val specTypeName: ClassName,
        val builderType: ClassName,
        val resultType: ClassName,
        val resultSupertype: KSType?,
        val specType: KSType,
        val builderVisibility: KModifier,
        val resultVisibility: KModifier,
        val required: List<RequiredProperty>,
        val requiresConfiguration: Boolean,
    )

    private class ResultSupertype(
        val type: KSType,
        val declaration: KSClassDeclaration,
        val typeName: TypeName,
        val visibility: KModifier,
    )

    private class ChildScope(
        val name: String,
        val parameters: List<Parameter>,
        val blockName: String,
        val blockTypeName: TypeName,
        val child: ChildSpec,
    ) {
        var fieldName: String = "${name}Field"
    }

    private fun decapitalize(name: String): String =
        if (name.length >= 2 && name[1].isUpperCase()) name else name.replaceFirstChar { it.lowercase() }

    /**
     * Returns `true` when [name] is a simple identifier that can name a
     * generated declaration. Names with separators or other characters cannot
     * be emitted as class or function names.
     */
    private fun isSimpleIdentifier(name: String): Boolean =
        NameAllocator(preallocateKeywords = false).newName(name) == name

    /**
     * The names of the generated classes and function for one DslBuilder
     * interface, derived from the interface name and overridden by the
     * [DslBuilder][top.ltfan.dslutilities.DslBuilder] annotation arguments.
     */
    private class Names(
        val resultName: String,
        val builderName: String,
        val functionName: String,
        val generateFunction: Boolean,
    ) {
        fun builderType(packageName: String): ClassName = ClassName(packageName, builderName)

        fun resultType(packageName: String): ClassName = ClassName(packageName, resultName)

        companion object {
            fun of(specName: String, annotation: KSAnnotation?): Names {
                val baseName = derivedBaseName(specName)
                val defaultResult = if (baseName == specName) "${specName}Result" else baseName
                val defaultBuilder = "${specName}Builder"
                val defaultFunction = "build$baseName"
                val resultName = annotation?.string("resultName").takeUnless { it.isNullOrEmpty() } ?: defaultResult
                val builderName = annotation?.string("builderName").takeUnless { it.isNullOrEmpty() } ?: defaultBuilder
                val generateFunction = annotation?.boolean("generateFunction") ?: true
                val functionName = annotation?.string("functionName").takeUnless { it.isNullOrEmpty() }
                    ?: defaultFunction.takeIf { generateFunction }.orEmpty()
                return Names(resultName, builderName, functionName, generateFunction)
            }
        }
    }

    private companion object {
        const val DSL_BUILDER_ANNOTATION = "top.ltfan.dslutilities.DslBuilder"
        const val DSL_VALUE_ANNOTATION = "top.ltfan.dslutilities.DslValue"
        const val DSL_LIST_ANNOTATION = "top.ltfan.dslutilities.DslList"
        const val DSL_CHILD_ANNOTATION = "top.ltfan.dslutilities.DslChild"
        const val DSL_VALIDATOR_NAME = "top.ltfan.dslutilities.DslValidator"
        const val DSL_MAPPER_NAME = "top.ltfan.dslutilities.DslMapper"

        /** Prefix of the compiler-internal annotations that are never copied. */
        const val KOTLIN_INTERNAL_PREFIX = "kotlin.internal."

        /** Compiler marker annotations that KotlinPoet expresses through syntax. */
        val EXTENSION_FUNCTION_TYPE = ClassName("kotlin", "ExtensionFunctionType")
        val PARAMETER_NAME = ClassName("kotlin", "ParameterName")
        val UNSAFE_VARIANCE = ClassName("kotlin", "UnsafeVariance")

        /**
         * Type annotations that are compiler markers rather than user-visible
         * annotations; KotlinPoet expresses their meaning through the type syntax
         * instead of copying them.
         */
        val IGNORED_ANNOTATIONS = setOf(
            EXTENSION_FUNCTION_TYPE,
            PARAMETER_NAME,
            UNSAFE_VARIANCE,
        ).mapTo(mutableSetOf()) { it.canonicalName }

        /**
         * Names declared by generated function bodies and the generated builder;
         * the templates below use the same constants, so a reference that reuses
         * one of them receives an aliased import.
         */
        const val VALUE_PARAMETER = "newValue"
        const val STORED_VALUE = "stored"
        const val SNAPSHOT = "snapshot"
        const val ELEMENT = "element"
        const val BUILD_FUNCTION = "build"
        const val BLOCK_PARAMETER = "block"
        const val BUILDER_LOCAL = "builder"
        const val CHILD_BUILDER_LOCAL = "childBuilder"

        /**
         * The names that every generated body and the builder declare and that
         * the templates emit as fixed text, registered per file up front. The
         * block, builder and child builder locals are allocated per function scope
         * instead, because their names depend on that function's parameter names.
         */
        val GENERATED_NAMES = setOf(VALUE_PARAMETER, STORED_VALUE, SNAPSHOT, ELEMENT, BUILD_FUNCTION)

        val REQUIRE = MemberName("kotlin", "require")
        val REQUIRE_NOT_NULL = MemberName("kotlin", "requireNotNull")
        val MUTABLE_LIST_OF = MemberName("kotlin.collections", "mutableListOf")
        val TO_LIST = MemberName("kotlin.collections", "toList", isExtension = true)
    }
}

private fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? =
    annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

/**
 * The name of a DslBuilder interface without its `Dsl` suffix; the name
 * itself when it has no suffix or the suffix is the whole name. The
 * result, builder and function names derive from this base.
 */
private fun derivedBaseName(specName: String): String =
    specName.removeSuffix("Dsl").takeIf { it.isNotEmpty() } ?: specName

/**
 * Merges the repeated annotations of [sources] into the specs to emit.
 * Each source contributes its own occurrence count; the highest count
 * of any single source wins and the specs keep the order of their first
 * appearance, so repeated annotations of one source stay repeated while
 * overlapping sources do not duplicate them.
 */
private fun mergeAnnotations(sources: List<List<AnnotationSpec>>): List<AnnotationSpec> {
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

/** Unsigned integral types, which KotlinPoet defines no constants for. */
private val UINT = ClassName("kotlin", "UInt")
private val ULONG = ClassName("kotlin", "ULong")
private val USHORT = ClassName("kotlin", "UShort")
private val UBYTE = ClassName("kotlin", "UByte")

/**
 * One supported `@DslValue.initial` type: the class it parses, the detail
 * of a constant that does not fit, and the literal it renders to.
 */
private class InitialFormat(
    val type: ClassName,
    val detail: String,
    val parse: (String) -> CodeBlock?,
)

/**
 * The supported `@DslValue.initial` types, in the order the diagnostic
 * lists them. The dispatch and the message derive from this table, so
 * adding a type keeps both in sync.
 */
private val initialFormats = listOf(
    InitialFormat(BYTE, "not a Byte constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }
            ?.let { literalNumber(if (it < 0) "($it).toByte()" else "$it.toByte()") }
    },
    InitialFormat(SHORT, "not a Short constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
            ?.let { literalNumber(if (it < 0) "($it).toShort()" else "$it.toShort()") }
    },
    InitialFormat(INT, "not an Int constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.let { literalNumber(it.toString()) }
    },
    InitialFormat(LONG, "not a Long constant") { initial ->
        initial.toLongOrNull()?.let {
            literalNumber(if (it == Long.MIN_VALUE) "Long.MIN_VALUE" else "${it}L")
        }
    },
    InitialFormat(UBYTE, "not a UByte constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in 0..UByte.MAX_VALUE.toLong() }
            ?.let { literalNumber("$it.toUByte()") }
    },
    InitialFormat(USHORT, "not a UShort constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in 0..UShort.MAX_VALUE.toLong() }
            ?.let { literalNumber("$it.toUShort()") }
    },
    InitialFormat(UINT, "not a UInt constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }
            ?.let { literalNumber("${it}u") }
    },
    InitialFormat(ULONG, "not a ULong constant") { initial ->
        initial.toULongOrNull()?.let { literalNumber("${it}uL") }
    },
    InitialFormat(FLOAT, "not a finite Float constant") { initial ->
        initial.toFloatOrNull()?.takeIf(Float::isFinite)
            ?.let { literalNumber("${formatFloatingPoint(it.toDouble())}f") }
    },
    InitialFormat(DOUBLE, "not a finite Double constant") { initial ->
        initial.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?.let { literalNumber(formatFloatingPoint(it)) }
    },
    InitialFormat(BOOLEAN, "not a Boolean constant") { initial ->
        when (initial) {
            "true", "false" -> literalNumber(initial)
            else -> null
        }
    },
    InitialFormat(CHAR, "not a single character") { initial ->
        if (initial.length == 1) CodeBlock.of("'%L'", escapeCharLiteral(initial[0])) else null
    },
    InitialFormat(STRING, "not a String constant") { CodeBlock.of("%S", it) },
)

/** Renders [text] as a literal expression. */
private fun literalNumber(text: String): CodeBlock = CodeBlock.of("%L", text)

/** Renders a floating point value so that it keeps its floating point type. */
private fun formatFloatingPoint(value: Double): String {
    val rendered = value.toString()
    return if (rendered.any { it == '.' || it == 'e' || it == 'E' }) rendered else "$rendered.0"
}

/**
 * Returns the string argument [name] when the annotation provides it
 * explicitly; an argument that fell back to its default reads as absent.
 */
private fun KSAnnotation.providedString(name: String): String? {
    val argument = arguments.firstOrNull { it.name?.asString() == name } ?: return null
    return if (argument.isDefault()) null else argument.value as? String
}

/** Returns whether this declaration has the qualified name of [className]. */
private fun KSDeclaration.isClass(className: ClassName): Boolean =
    qualifiedName?.asString() == className.canonicalName

/**
 * Returns whether this type cannot be rendered as a regular type
 * reference: it is a kept alias (an unbound star projection
 * kept it) or one of its arguments is a star projection.
 */
private fun KSType.hasUnboundProjection(): Boolean =
    declaration is KSTypeAlias || arguments.any { it.type == null }

/**
 * Returns whether this annotation is the compiler marker [className]. An
 * annotation that resolves to a declaration is matched by its qualified
 * name, so a user annotation that reuses the marker's short name is
 * not mistaken for it. A marker like `kotlin.ExtensionFunctionType`
 * is synthetic: KSP exposes it without a resolvable declaration,
 * so the short name derived from the constant is the fallback.
 */
private fun KSAnnotation.isMarker(className: ClassName): Boolean {
    val qualifiedName = annotationType.resolve().declaration.qualifiedName?.asString()
    return if (qualifiedName != null) {
        qualifiedName == className.canonicalName
    } else {
        shortName.asString() == className.simpleName
    }
}

/** Returns whether this type is `kotlin.Unit`. */
private fun KSType.isUnit(): Boolean = declaration.isClass(UNIT)

private fun KSAnnotation.string(name: String): String? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String

private fun KSAnnotation.boolean(name: String): Boolean? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean

private fun KSAnnotation.type(name: String): KSType? =
    when (val value = arguments.firstOrNull { it.name?.asString() == name }?.value) {
        is KSType -> value
        is KSClassDeclaration -> value.asType(emptyList())
        else -> null
    }

private fun KSAnnotation.typeArray(name: String): List<KSType> =
    (arguments.firstOrNull { it.name?.asString() == name }?.value as? List<*>)
        ?.mapNotNull { element ->
            when (element) {
                is KSType -> element
                is KSClassDeclaration -> element.asType(emptyList())
                else -> null
            }
        }
        .orEmpty()

private fun escapeCharLiteral(character: Char): String = when (character) {
    '\\' -> "\\\\"
    '\'' -> "\\'"
    '\n' -> "\\n"
    '\r' -> "\\r"
    '\t' -> "\\t"
    '$' -> "\\$"
    else -> character.toString()
}

/** Provides [DslProcessor] to the KSP runtime through the service loader. */
class DslProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): DslProcessor =
        DslProcessor(environment.codeGenerator, environment.logger)
}
