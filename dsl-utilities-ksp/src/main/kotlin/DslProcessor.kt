package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
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
                                "%L(%L.validate($VALUE_PARAMETER)) { %L }",
                                context.member(REQUIRE),
                                property.validator.code(context),
                                literalString(property.message)
                            )
                        }
                        addStatement("%N = $VALUE_PARAMETER", property.nameField())
                    } else {
                        addStatement("val $STORED_VALUE = %L.toStored($VALUE_PARAMETER)", property.mapper.code(context))
                        if (property.validator != null) {
                            addStatement(
                                "%L(%L.validate($STORED_VALUE)) { %L }",
                                context.member(REQUIRE),
                                property.validator.code(context),
                                literalString(property.message)
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
                        "%L(%L.validate(%N)) { %L }\n",
                        context.member(REQUIRE),
                        property.validator.code(context),
                        property.nameField(),
                        literalString(property.message),
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
                    "%L(%L.validate(%N)) { %L }",
                    context.member(REQUIRE),
                    property.validator.code(context),
                    property.name,
                    literalString(property.message),
                )
            }
        }
        for (property in listProperties) {
            if (property.validator != null) {
                buildCode.beginControlFlow("for ($ELEMENT in %N)", property.nameField())
                buildCode.addStatement(
                    "%L(%L.validate($ELEMENT)) { %L }",
                    context.member(REQUIRE),
                    property.validator.code(context),
                    literalString(property.message),
                )
                buildCode.endControlFlow()
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
            if (!acceptsChildResult(resolvedElementType, child, checker)) {
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
}

/** Provides [DslProcessor] to the KSP runtime through the service loader. */
class DslProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): DslProcessor =
        DslProcessor(environment.codeGenerator, environment.logger)
}
