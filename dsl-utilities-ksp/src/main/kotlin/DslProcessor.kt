package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
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

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        for (symbol in resolver.getSymbolsWithAnnotation("top.ltfan.dslutilities.DslBuilder")) {
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

        val checker = Checker(resolver, logger)
        val specQualifiedName = spec.qualifiedName?.asString()
        if (specQualifiedName == null) {
            checker.report(
                spec,
                "@DslBuilder applies to declarations with a qualified name, but $specName is local or anonymous."
            )
            return handled(checker)
        }
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
        val hierarchy = Hierarchy(checker)
        for (member in hierarchy.abstractProperties(spec)) {
            val property = member.declaration
            val name = property.simpleName.asString()
            val type = checker.substituteType(property.type.resolve(), member.environment)
            val dslValue = property.annotation(DSL_VALUE_ANNOTATION)
            val dslList = property.annotation(DSL_LIST_ANNOTATION)

            if (dslList != null) {
                listProperty(name, property, type, dslList, checker)?.let(listProperties::add)
            } else if (!property.isMutable) {
                requiredProperty(
                    name,
                    property,
                    type,
                    member.environment,
                    dslValue,
                    checker
                )?.let(requiredProperties::add)
            } else if (dslValue != null) {
                valueProperty(name, property, type, member.environment, dslValue, checker)?.let(valueProperties::add)
            } else {
                checker.report(property, "Property $name must be annotated with @DslValue or @DslList.")
            }
        }

        val abstractFunctions = hierarchy.abstractFunctions(spec)
        for ((name, overloads) in abstractFunctions.groupBy { it.declaration.simpleName.asString() }) {
            if (overloads.size > 1) {
                checker.report(
                    spec,
                    "DslBuilder interface $specName overloads child-scope function $name; overloaded child-scope names are not supported."
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

        val childOwners = mutableMapOf<String, String>()
        for (property in listProperties) {
            for (child in property.children) {
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

        val supertypeType =
            annotation?.type("supertype")?.takeIf { it.declaration.qualifiedName?.asString() != "kotlin.Unit" }
        val supertypeDeclaration = supertypeType?.declaration as? KSClassDeclaration
        val supertypeTypeName = supertypeType?.let { checker.renderTypeName(spec, it) }
        val supertypeOverrides = mutableSetOf<String>()
        var supertypeInternal = false
        val resultProperties = requiredProperties.map { it.name to it.typeName } +
                valueProperties.map { it.name to it.typeName } +
                listProperties.map { it.name to LIST.parameterizedBy(it.elementTypeName) } +
                childScopes.map { it.name to classNameOf(it.child.resultQualifiedName) }
        if (supertypeDeclaration != null && supertypeTypeName != null) {
            supertypeInternal = effectiveVisibility(supertypeDeclaration, checker) == KModifier.INTERNAL
            val resultPropertyTypes = resultProperties.toMap()
            val environment = supertypeDeclaration.typeParameters.zip(supertypeType.arguments)
                .mapNotNull { (parameter, argument) ->
                    val argumentType = argument.type?.resolve() ?: return@mapNotNull null
                    parameter to checker.substituteType(argumentType, emptyMap())
                }
                .toMap()
            for (member in hierarchy.allProperties(supertypeDeclaration, environment)) {
                val property = member.declaration
                val name = property.simpleName.asString()
                val rendered = checker.renderTypeName(
                    property,
                    checker.substituteType(property.type.resolve(), member.environment)
                )
                    ?: break
                val provided = resultPropertyTypes[name]
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

                    provided != rendered ->
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
        val resultVisibility =
            if (visibility == KModifier.INTERNAL || supertypeInternal) KModifier.INTERNAL else KModifier.PUBLIC

        val builderTypeName = ClassName(packageName, names.builderName)
        val resultTypeName = ClassName(packageName, names.resultName)
        val builderType = builderType(
            specQualifiedName,
            builderTypeName,
            resultTypeName,
            listOf(visibility),
            resultVisibility,
            requiredProperties,
            valueProperties,
            listProperties,
            childScopes,
        )
        val resultType =
            resultType(resultTypeName, resultVisibility, resultProperties, supertypeTypeName, supertypeOverrides)
        val elementFunctions = elementFunctions(specQualifiedName, listOf(visibility), listProperties)
        val buildFunction = if (names.generateFunction) {
            buildFunction(
                names,
                specQualifiedName,
                resultVisibility,
                builderTypeName,
                resultTypeName,
                requiredProperties,
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
            .apply { buildFunction?.let(::addFunction) }
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
        specQualifiedName: String,
        builderTypeName: ClassName,
        resultTypeName: ClassName,
        visibility: List<KModifier>,
        memberVisibility: KModifier,
        requiredProperties: List<RequiredProperty>,
        valueProperties: List<ValueProperty>,
        listProperties: List<ListProperty>,
        childScopes: List<ChildScope>,
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
            .addSuperinterface(classNameOf(specQualifiedName))

        for (property in requiredProperties) {
            // The same-named property initialized from the constructor
            // parameter emits the parameter as an `override val` of the
            // primary constructor.
            builder.addProperty(
                PropertySpec.builder(property.name, property.typeName, KModifier.OVERRIDE)
                    .initializer(property.name)
                    .build()
            )
        }
        for (property in valueProperties) {
            builder.addProperty(
                PropertySpec.builder(property.nameField(), property.storageTypeName, KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("%L", property.initialExpression)
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
                    .initializer("mutableListOf()")
                    .build()
            )
        }
        for (property in childScopes) {
            builder.addProperty(
                PropertySpec.builder(
                    property.nameField(),
                    classNameOf(property.child.resultQualifiedName).asNullable(),
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
                        addStatement("return %L.toValue(%N)", invocation(property.mapper), property.nameField())
                    }
                }
                .build()
            val setter = FunSpec.setterBuilder()
                .addParameter("newValue", property.typeName)
                .apply {
                    if (property.mapper == null) {
                        if (property.validator != null) {
                            addStatement(
                                "require(%L.validate(newValue)) { %S }",
                                invocation(property.validator),
                                property.message
                            )
                        }
                        addStatement("%N = newValue", property.nameField())
                    } else {
                        addStatement("val stored = %L.toStored(newValue)", invocation(property.mapper))
                        if (property.validator != null) {
                            addStatement(
                                "require(%L.validate(stored)) { %S }",
                                invocation(property.validator),
                                property.message
                            )
                        }
                        addStatement("%N = stored", property.nameField())
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
            val validateDefault = property.initialExpression != "null" || property.validatorAcceptsNull
            if (property.validator != null && validateDefault) {
                builder.addInitializerBlock(
                    CodeBlock.of(
                        "require(%L.validate(%N)) { %S }\n",
                        invocation(property.validator),
                        property.nameField(),
                        property.message,
                    )
                )
            }
        }

        for (property in listProperties) {
            val setter = FunSpec.setterBuilder()
                .addParameter("newValue", MUTABLE_LIST.parameterizedBy(property.elementTypeName))
                .addStatement("val snapshot = newValue.toList()")
                .addStatement("%N.clear()", property.nameField())
                .addStatement("%N.addAll(snapshot)", property.nameField())
                .build()
            builder.addProperty(
                PropertySpec.builder(
                    property.name,
                    MUTABLE_LIST.parameterizedBy(property.elementTypeName),
                    KModifier.OVERRIDE
                )
                    .mutable(true)
                    .getter(FunSpec.getterBuilder().addStatement("return %N", property.nameField()).build())
                    .setter(setter)
                    .build()
            )
        }

        for (property in childScopes) {
            val blockType = LambdaTypeName.get(
                receiver = classNameOf(property.child.specQualifiedName),
                returnType = UNIT,
            )
            val parameters = property.parameters.map { ParameterSpec.builder(it.name, it.typeName).build() } +
                    ParameterSpec.builder("block", blockType).build()
            val arguments = property.child.required.joinToString(", ") { "${it.name} = ${it.name}" }
            builder.addFunction(
                FunSpec.builder(property.name)
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameters(parameters)
                    .addStatement(
                        "%N = %T(%L).apply(block).build()",
                        property.nameField(),
                        classNameOf(property.child.builderQualifiedName),
                        arguments,
                    )
                    .build()
            )
        }

        val buildCode = CodeBlock.builder()
        for (property in requiredProperties) {
            if (property.validator != null) {
                buildCode.addStatement(
                    "require(%L.validate(%N)) { %S }",
                    invocation(property.validator),
                    property.name,
                    property.message,
                )
            }
        }
        for (property in listProperties) {
            if (property.validator != null) {
                buildCode.addStatement("for (element in %N) {", property.nameField())
                buildCode.addStatement(
                    "require(%L.validate(element)) { %S }",
                    invocation(property.validator),
                    property.message,
                )
                buildCode.addStatement("}")
            }
        }
        val argumentParts = mutableListOf<String>()
        for (property in requiredProperties) argumentParts += "${property.name} = ${property.name}"
        for (property in valueProperties) argumentParts += "${property.name} = ${property.name}"
        for (property in listProperties) argumentParts += "${property.name} = ${property.nameField()}.toList()"
        for (property in childScopes) {
            argumentParts += "${property.name} = requireNotNull(${property.nameField()}) { \"Property ${property.name} is required.\" }"
        }
        val arguments = argumentParts.joinToString(", ")
        builder.addFunction(
            FunSpec.builder("build")
                .addModifiers(memberVisibility)
                .returns(resultTypeName)
                .addCode(buildCode.build())
                .addStatement("return %T(%L)", resultTypeName, arguments)
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
                    .initializer(name)
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
        specQualifiedName: String,
        visibility: List<KModifier>,
        listProperties: List<ListProperty>,
    ): ElementFunctions {
        val functions = mutableListOf<FunSpec>()
        val shorthands = mutableListOf<PropertySpec>()
        for (property in listProperties) {
            for (child in property.children) {
                val arguments = child.required.joinToString(", ") { "${it.name} = ${it.name}" }
                functions += FunSpec.builder(child.functionName)
                    .addModifiers(visibility + KModifier.INLINE)
                    .receiver(classNameOf(specQualifiedName))
                    .apply {
                        for (required in child.required) addParameter(required.name, required.typeName)
                    }
                    .addParameter(
                        ParameterSpec.builder(
                            "block",
                            LambdaTypeName.get(
                                receiver = classNameOf(child.specQualifiedName),
                                returnType = UNIT,
                            ),
                        )
                            .defaultValue("{}")
                            .build()
                    )
                    .addStatement(
                        "%N.add(%T(%L).apply(block).build())",
                        property.name,
                        classNameOf(child.builderQualifiedName),
                        arguments,
                    )
                    .build()
                if (child.required.isEmpty()) {
                    shorthands += PropertySpec.builder(child.functionName, UNIT)
                        .addModifiers(visibility)
                        .receiver(classNameOf(specQualifiedName))
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
        specQualifiedName: String,
        visibility: KModifier,
        builderTypeName: ClassName,
        resultTypeName: ClassName,
        requiredProperties: List<RequiredProperty>,
    ): FunSpec {
        val arguments = requiredProperties.joinToString(", ") { "${it.name} = ${it.name}" }
        return FunSpec.builder(names.functionName)
            .addModifiers(visibility, KModifier.INLINE)
            .apply {
                for (property in requiredProperties) {
                    addParameter(ParameterSpec.builder(property.name, property.typeName).build())
                }
            }
            .addParameter(
                ParameterSpec.builder(
                    "block",
                    LambdaTypeName.get(receiver = classNameOf(specQualifiedName), returnType = UNIT),
                )
                    .defaultValue("{}")
                    .build()
            )
            .returns(resultTypeName)
            .addStatement("val builder = %T(%L)", builderTypeName, arguments)
            .addStatement("builder.block()")
            .addStatement("return builder.build()")
            .build()
    }

    /** The receiver expression that invokes an object or a no-argument class. */
    private fun invocation(prefix: String): CodeBlock {
        val type = classNameOf(prefix.removeSuffix("()"))
        return if (prefix.endsWith("()")) CodeBlock.of("%T()", type) else CodeBlock.of("%T", type)
    }

    private fun ValueProperty.nameField(): String = "${name}Field"

    private fun ListProperty.nameField(): String = "${name}Field"

    private fun ChildScope.nameField(): String = "${name}Field"

    /**
     * Resolves a fully qualified name into a [ClassName]; nested classes
     * are expressed through their nesting so generated code references them
     * without package-level ambiguity.
     */
    private fun classNameOf(qualifiedName: String): ClassName = ClassName.bestGuess(qualifiedName)

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
        environment: Map<KSTypeParameter, KSType>,
        annotation: KSAnnotation?,
        checker: Checker,
    ): RequiredProperty? {
        val typeName = checker.renderTypeName(property, checker.substituteType(property.type.resolve(), environment))
            ?: return null
        if (annotation?.string("initial")?.isNotEmpty() == true) {
            checker.report(property, "@DslValue.initial applies to var properties; $name is a val.")
            return null
        }
        val mapperType = annotation?.type("mapper")
        if (mapperType != null && mapperType.declaration.qualifiedName?.asString() != "kotlin.Unit") {
            checker.report(property, "@DslValue.mapper applies to var properties; $name is a val.")
            return null
        }
        val validator = checker.validator(property, name, annotation?.type("validator"), type)
        if (!checker.valid) return null
        return RequiredProperty(name, typeName, validator?.prefix, checker.message(annotation, name))
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
        environment: Map<KSTypeParameter, KSType>,
        annotation: KSAnnotation,
        checker: Checker,
    ): ValueProperty? {
        if (type.declaration.qualifiedName?.asString() == "kotlin.collections.MutableList") {
            checker.report(
                property,
                "Property $name has a MutableList type; list properties are declared with @DslList."
            )
            return null
        }
        val typeName = checker.renderTypeName(property, checker.substituteType(property.type.resolve(), environment))
            ?: return null
        val mapper = checker.mapper(property, name, annotation.type("mapper"), type)
        if (!checker.valid) return null
        val initial = annotation.string("initial").orEmpty().takeIf { it.isNotEmpty() }
        when (initial) {
            null if !type.isMarkedNullable -> {
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
        val initialExpression = if (initial == null) {
            "null"
        } else {
            val literal = checker.literal(property, name, initial, type) ?: return null
            if (mapper != null) "${mapper.prefix}.toStored($literal)" else literal
        }
        val validator = checker.validator(
            property,
            name,
            annotation.type("validator"),
            mapper?.storedType ?: type,
        )
        if (!checker.valid) return null
        return ValueProperty(
            name = name,
            typeName = typeName,
            storageTypeName = mapper?.storageTypeName ?: typeName,
            initialExpression = initialExpression,
            mapper = mapper?.prefix,
            validator = validator?.prefix,
            validatorAcceptsNull = validator?.acceptsNull == true,
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
        annotation: KSAnnotation,
        checker: Checker,
    ): ListProperty? {
        if (!property.isMutable) {
            checker.report(property, "@DslList applies to var properties; $name is a val.")
            return null
        }
        if (type.declaration.qualifiedName?.asString() != "kotlin.collections.MutableList" || type.arguments.size != 1) {
            checker.report(
                property,
                "Property $name annotated with @DslList has a type other than MutableList of the element type."
            )
            return null
        }
        val elementType = type.arguments.single().type?.resolve() ?: run {
            checker.report(property, "Property $name has an unsupported element type.")
            return null
        }
        val elementTypeName = checker.renderTypeName(property, elementType) ?: return null
        val validator = checker.validator(property, name, annotation.type("validator"), elementType)
        if (!checker.valid) return null
        val validatorPrefix = validator?.prefix

        val children = mutableListOf<ChildSpec>()
        for (argumentType in annotation.typeArray("children")) {
            if (argumentType.isError) {
                checker.reportUnresolved(property, "A child of @DslList property $name is not resolvable yet.")
                return null
            }
            val childDeclaration = argumentType.declaration as? KSClassDeclaration ?: run {
                checker.report(property, "The children of @DslList property $name must be DslBuilder interfaces.")
                return null
            }
            val child = resolveChild(childDeclaration, checker) ?: return null
            val elementQualifiedName = elementType.makeNotNullable().declaration.qualifiedName?.asString()
            val acceptsResult = elementQualifiedName == "kotlin.Any" ||
                    elementQualifiedName == child.resultQualifiedName ||
                    child.resultSupertype?.let(elementType::isAssignableFrom) == true
            if (!acceptsResult) {
                checker.report(
                    property,
                    "Child ${child.specQualifiedName} produces ${child.resultQualifiedName}, which is not assignable to the element type $elementTypeName of @DslList property $name."
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
        return ListProperty(name, elementTypeName, validatorPrefix, checker.message(annotation, name), children)
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
        if (!function.isAbstract) {
            checker.report(function, "@DslChild function $name declares a body; the processor generates the body.")
            return null
        }
        if (function.extensionReceiver != null) {
            checker.report(function, "@DslChild function $name must not declare an extension receiver.")
            return null
        }
        if (function.typeParameters.isNotEmpty()) {
            checker.report(function, "@DslChild function $name must not declare type parameters.")
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
        val declaredBlockType = blockParameter.type.resolve()
        if (declaredBlockType.isError) {
            checker.reportUnresolved(function, "The last parameter of @DslChild function $name is not resolvable yet.")
            return null
        }
        // The block type of a child scope inherited from a generic base
        // carries the base's type parameters; substituting them resolves the
        // receiver from the perspective of the analyzed interface. The shape
        // checks stay on the declared type because substitution does not
        // carry over the receiver and suspend markers.
        val isReceiverStyle = declaredBlockType.annotations.any { it.shortName.asString() == EXTENSION_FUNCTION_TYPE }
        val isSuspend = declaredBlockType.isSuspendFunctionType
        val blockType = checker.substituteType(declaredBlockType, environment)
        val arguments = blockType.arguments
        if ((!declaredBlockType.isFunctionType && !isSuspend) || arguments.size != (if (isReceiverStyle) 2 else 1)) {
            checker.report(
                function,
                "The last parameter of @DslChild function $name must be a function type with the child DslBuilder interface as receiver and a Unit return type."
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
        val blockReturnType = arguments.last().type?.resolve()
        if (blockReturnType?.declaration?.qualifiedName?.asString() != "kotlin.Unit") {
            checker.report(
                function,
                "The last parameter of @DslChild function $name must return Unit."
            )
            return null
        }
        val receiverType = arguments[0].type?.resolve() ?: run {
            checker.report(
                function,
                "The last parameter of @DslChild function $name must be a function type with the child DslBuilder interface as receiver."
            )
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
            val parameterTypeName =
                checker.renderTypeName(function, checker.substituteType(parameter.type.resolve(), environment))
                    ?: return null
            if (parameterName != required.name || parameterTypeName != required.typeName) {
                checker.report(
                    function,
                    "Parameter $parameterName of @DslChild function $name does not match required property ${required.name} of type ${required.typeName} of the child."
                )
                return null
            }
            scopeParameters += Parameter(parameterName, parameterTypeName)
        }
        return ChildScope(name, scopeParameters, child)
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
        val specQualifiedName = declaration.qualifiedName?.asString() ?: run {
            checker.report(declaration, "Child $specName has no qualified name.")
            return null
        }
        val names = Names.of(specName, annotation)
        val packageName = declaration.packageName.asString()
        fun qualify(name: String): String = if (packageName.isEmpty()) name else "$packageName.$name"
        val resultSupertype = annotation.type("supertype")
            ?.takeIf { it.declaration.qualifiedName?.asString() != "kotlin.Unit" }
        if (resultSupertype?.isError == true) {
            checker.reportUnresolved(declaration, "The result supertype of child $specName is not resolvable yet.")
            return null
        }
        val required = mutableListOf<RequiredProperty>()
        for (member in Hierarchy(checker).abstractProperties(declaration)) {
            val property = member.declaration
            if (property.isMutable) continue
            val typeName =
                checker.renderTypeName(property, checker.substituteType(property.type.resolve(), member.environment))
                    ?: return null
            required += RequiredProperty(property.simpleName.asString(), typeName, null, "")
        }
        return ChildSpec(
            functionName = decapitalize(specName.removeSuffix("Dsl").takeIf { it.isNotEmpty() } ?: specName),
            builderQualifiedName = qualify(names.builderName),
            resultQualifiedName = qualify(names.resultName),
            resultSupertype = resultSupertype,
            specQualifiedName = specQualifiedName,
            required = required,
        )
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
            val modifiers = declaration.modifiers
            when {
                Modifier.PRIVATE in modifiers || Modifier.PROTECTED in modifiers -> {
                    checker.report(
                        spec,
                        "The DslBuilder interface ${spec.simpleName.asString()} must be visible from the package level, but it is hidden by ${declaration.simpleName.asString()}."
                    )
                    return null
                }

                Modifier.INTERNAL in modifiers -> internal = true
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
    private class Hierarchy(private val checker: Checker) {
        fun abstractProperties(
            declaration: KSClassDeclaration,
            initialEnvironment: Map<KSTypeParameter, KSType> = emptyMap(),
        ): List<SubstitutedMember<KSPropertyDeclaration>> {
            val members = LinkedHashMap<String, SubstitutedMember<KSPropertyDeclaration>>()
            val seen = mutableSetOf<String>()
            walk(declaration, initialEnvironment) { member, environment ->
                if (member !is KSPropertyDeclaration) return@walk
                val name = member.simpleName.asString()
                if (!seen.add(name)) return@walk
                if (!member.isAbstractMember()) return@walk
                members[name] = SubstitutedMember(member, environment)
            }
            return members.values.toList()
        }

        fun abstractFunctions(declaration: KSClassDeclaration): List<SubstitutedMember<KSFunctionDeclaration>> {
            val members = LinkedHashMap<String, SubstitutedMember<KSFunctionDeclaration>>()
            val seen = mutableSetOf<String>()
            walk(declaration, emptyMap()) { member, environment ->
                if (member !is KSFunctionDeclaration) return@walk
                val signature = checker.functionSignature(member, environment)
                if (!seen.add(signature)) return@walk
                if (member.isAbstract) members[signature] = SubstitutedMember(member, environment)
            }
            return members.values.toList()
        }

        /**
         * Collects every property of the hierarchy, abstract or concrete, so the
         * caller can tell override candidates from members that must be provided;
         * the nearest declaration wins when a name is redeclared.
         */
        fun allProperties(
            declaration: KSClassDeclaration,
            initialEnvironment: Map<KSTypeParameter, KSType> = emptyMap(),
        ): List<SubstitutedMember<KSPropertyDeclaration>> {
            val members = LinkedHashMap<String, SubstitutedMember<KSPropertyDeclaration>>()
            walk(declaration, initialEnvironment) { member, environment ->
                if (member !is KSPropertyDeclaration) return@walk
                val name = member.simpleName.asString()
                if (name !in members) members[name] = SubstitutedMember(member, environment)
            }
            return members.values.toList()
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
            visit: (KSDeclaration, Map<KSTypeParameter, KSType>) -> Unit,
        ) {
            val pending = ArrayDeque<Pair<KSClassDeclaration, Map<KSTypeParameter, KSType>>>()
            pending += declaration to initialEnvironment
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val (current, environment) = pending.removeFirst()
                val name = current.qualifiedName?.asString() ?: continue
                if (!visited.add(name)) continue
                for (member in current.declarations) visit(member, environment)
                for (superType in current.superTypes) {
                    val resolved = superType.resolve()
                    if (resolved.isError) {
                        checker.reportUnresolved(
                            declaration,
                            "A supertype of the DslBuilder interface ${declaration.simpleName.asString()} is not resolvable yet."
                        )
                        return
                    }
                    val superDeclaration = resolved.declaration as? KSClassDeclaration ?: continue
                    val nextEnvironment = superDeclaration.typeParameters.zip(resolved.arguments)
                        .mapNotNull { (parameter, argument) ->
                            val argumentType = argument.type?.resolve() ?: return@mapNotNull null
                            parameter to checker.substituteType(argumentType, environment)
                        }
                        .toMap()
                    pending += superDeclaration to nextEnvironment
                }
            }
        }
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
        fun renderTypeName(symbol: KSNode, type: KSType): TypeName? {
            if (type.isError) {
                reportUnresolved(symbol, "The type of ${symbolDescription(symbol)} is not resolvable yet.")
                return null
            }
            return try {
                if (type.isFunctionType || type.isSuspendFunctionType) {
                    lambdaTypeName(symbol, type)
                } else {
                    classifierTypeName(type)
                }
            } catch (_: Exception) {
                report(symbol, "The type of ${symbolDescription(symbol)} is unsupported.")
                null
            }
        }

        /**
         * Renders a function type as a lambda so that the suspend modifier, the
         * extension receiver and the nullability survive rendering; parameters
         * stay unnamed because their names live in compiler annotations that
         * generic substitution does not carry over.
         */
        private fun lambdaTypeName(symbol: KSNode, type: KSType): TypeName {
            val arguments = type.arguments
            val isExtension = type.annotations.any { it.shortName.asString() == EXTENSION_FUNCTION_TYPE }
            val receiver = if (isExtension) {
                arguments.firstOrNull()?.type?.resolve()?.let { renderTypeName(symbol, it) }
            } else {
                null
            }
            val valueArguments = if (isExtension) arguments.drop(1) else arguments
            val parameters = valueArguments.dropLast(1).map { argument ->
                val argumentType = argument.type?.resolve()
                val typeName = argumentType?.let { renderTypeName(symbol, it) } ?: ANY.copy(nullable = true)
                val name = argumentType?.annotations
                    ?.firstOrNull { it.shortName.asString() == PARAMETER_NAME }
                    ?.arguments
                    ?.firstOrNull()
                    ?.value as? String
                if (name == null) ParameterSpec.unnamed(typeName) else ParameterSpec.builder(name, typeName).build()
            }
            val returnType = valueArguments.lastOrNull()?.type?.resolve()
                ?.let { renderTypeName(symbol, it) }
                ?: UNIT
            return LambdaTypeName.get(receiver = receiver, parameters = parameters, returnType = returnType)
                .copy(nullable = type.isMarkedNullable, suspending = type.isSuspendFunctionType)
                .annotated(type, ignoreExtensionMarker = true)
        }

        private fun classifierTypeName(type: KSType): TypeName {
            val typeName = type.toTypeName()
            return typeName.annotated(type, ignoreExtensionMarker = false)
        }

        private fun TypeName.annotated(
            type: KSType,
            ignoreExtensionMarker: Boolean,
        ): TypeName {
            val annotations = type.annotations.toList().mapNotNull { annotation ->
                val shortName = annotation.shortName.asString()
                if (ignoreExtensionMarker && shortName == EXTENSION_FUNCTION_TYPE) return@mapNotNull null
                val qualifiedName = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
                if (qualifiedName == null) {
                    null
                } else if (qualifiedName in IGNORED_ANNOTATIONS || qualifiedName.startsWith("kotlin.internal.")) {
                    null
                } else {
                    annotation.toAnnotationSpec()
                }
            }
            return if (annotations.isEmpty()) this else annotated(annotations)
        }

        /**
         * Renders a type for a diagnostic message without reporting failures; the
         * message accompanies an already-reported problem.
         */
        fun typeNameOrNull(type: KSType): TypeName? = try {
            if (type.isError) null else type.toTypeName()
        } catch (_: Exception) {
            null
        }

        private fun symbolDescription(symbol: KSNode): String = when (symbol) {
            is KSPropertyDeclaration -> "property ${symbol.simpleName.asString()}"
            is KSFunctionDeclaration -> "function ${symbol.simpleName.asString()}"
            else -> symbol.toString()
        }

        /**
         * Returns the validator invocation prefix together with whether the
         * validator accepts `null`, or `null` when no validator is configured or a
         * diagnostic was reported.
         */
        fun validator(
            property: KSPropertyDeclaration,
            propertyName: String,
            validatorType: KSType?,
            valueType: KSType,
        ): ValidatorInfo? {
            if (validatorType == null || validatorType.isUnit()) return null
            if (validatorType.isError) {
                reportUnresolved(property, "The validator of property $propertyName is not resolvable yet.")
                return null
            }
            val declaration = validatorType.declaration as? KSClassDeclaration ?: run {
                report(property, "The validator of property $propertyName must be a class or object.")
                return null
            }
            val dslValidatorArguments = findSuperTypeArguments(declaration, property, DSL_VALIDATOR_NAME)
            if (dslValidatorArguments == null) {
                report(property, "The validator of property $propertyName must implement DslValidator.")
                return null
            }
            val validatedType = dslValidatorArguments.singleOrNull()
            if (validatedType == null || !validatedType.isAssignableFrom(valueType)) {
                report(
                    property,
                    "The validator of property $propertyName validates a type that does not accept ${
                        typeNameOrNull(valueType) ?: "the property"
                    } values."
                )
                return null
            }
            val prefix = instantiationPrefix(property, propertyName, declaration, "validator") ?: return null
            return ValidatorInfo(prefix, validatedType.isMarkedNullable)
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
            if (mapperType == null || mapperType.isUnit()) return null
            if (mapperType.isError) {
                reportUnresolved(property, "The mapper of property $propertyName is not resolvable yet.")
                return null
            }
            val declaration = mapperType.declaration as? KSClassDeclaration ?: run {
                report(property, "The mapper of property $propertyName must be a class or object.")
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
            val storedType = dslMapperArguments[0]!!
            val valueType = dslMapperArguments[1]!!
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
            val prefix = instantiationPrefix(property, propertyName, declaration, "mapper") ?: return null
            val storageTypeName = renderTypeName(property, storedType) ?: return null
            return MapperInfo(prefix, storageTypeName, storedType)
        }

        private fun instantiationPrefix(
            property: KSPropertyDeclaration,
            propertyName: String,
            declaration: KSClassDeclaration,
            role: String,
        ): String? {
            val prefix = declaration.qualifiedName?.asString()
            if (prefix == null) {
                report(property, "The $role of property $propertyName has no qualified name.")
                return null
            }
            return when {
                declaration.isCompanionObject || declaration.classKind == ClassKind.OBJECT -> prefix
                declaration.classKind == ClassKind.CLASS ->
                    if (declaration.primaryConstructor?.parameters?.isEmpty() == true) "$prefix()" else {
                        report(
                            property,
                            "The $role of property $propertyName is a class without a no-argument constructor."
                        )
                        null
                    }

                else -> {
                    report(property, "The $role of property $propertyName must be a class or object.")
                    null
                }
            }
        }

        /**
         * Renders a compile-time constant written in string form as a Kotlin
         * literal expression of the property type.
         */
        fun literal(property: KSPropertyDeclaration, propertyName: String, initial: String, type: KSType): String? {
            fun fail(detail: String): String? {
                report(property, "@DslValue.initial of property $propertyName is \"$initial\", which is $detail.")
                return null
            }
            return when (type.makeNotNullable().declaration.qualifiedName?.asString()) {
                "kotlin.Int" -> initial.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toString()
                    ?: fail("not an Int constant")

                "kotlin.Long" -> initial.toLongOrNull()?.let { "${it}L" } ?: fail("not a Long constant")
                "kotlin.Short" -> initial.toLongOrNull()?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
                    ?.let { "$it.toShort()" }
                    ?: fail("not a Short constant")

                "kotlin.Byte" -> initial.toLongOrNull()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }
                    ?.let { "$it.toByte()" }
                    ?: fail("not a Byte constant")

                "kotlin.Double" -> initial.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?.let(::formatFloatingPoint)
                    ?: fail("not a finite Double constant")

                "kotlin.Float" -> initial.toFloatOrNull()?.takeIf(Float::isFinite)
                    ?.let { "${formatFloatingPoint(it.toDouble())}f" }
                    ?: fail("not a finite Float constant")

                "kotlin.Boolean" -> when (initial) {
                    "true", "false" -> initial
                    else -> fail("not a Boolean constant")
                }

                "kotlin.Char" -> if (initial.length == 1) "'${escapeCharLiteral(initial[0])}'" else fail("not a single character")
                "kotlin.String" -> "\"${escapeStringLiteral(initial)}\""
                "kotlin.UInt" -> initial.toLongOrNull()?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }?.let { "${it}u" }
                    ?: fail("not a UInt constant")

                "kotlin.ULong" -> initial.toULongOrNull()?.let { "${it}uL" } ?: fail("not a ULong constant")
                "kotlin.UShort" -> initial.toLongOrNull()?.takeIf { it in 0..UShort.MAX_VALUE.toLong() }
                    ?.let { "$it.toUShort()" }
                    ?: fail("not a UShort constant")

                "kotlin.UByte" -> initial.toLongOrNull()?.takeIf { it in 0..UByte.MAX_VALUE.toLong() }
                    ?.let { "$it.toUByte()" }
                    ?: fail("not a UByte constant")

                else -> {
                    report(
                        property,
                        "@DslValue.initial of property $propertyName supports Byte, Short, Int, Long, UByte, UShort, UInt, ULong, Float, Double, Boolean, Char, and String constants."
                    )
                    null
                }
            }
        }

        private fun formatFloatingPoint(value: Double): String {
            val rendered = value.toString()
            return if (rendered.any { it == '.' || it == 'e' || it == 'E' }) rendered else "$rendered.0"
        }

        private fun KSType.isUnit(): Boolean =
            declaration.qualifiedName?.asString() == "kotlin.Unit"

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
                    val resolved = superType.resolve()
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

    private class ValidatorInfo(
        val prefix: String,
        val acceptsNull: Boolean,
    )

    private data class MapperInfo(
        val prefix: String,
        val storageTypeName: TypeName,
        val storedType: KSType,
    )

    private class RequiredProperty(
        val name: String,
        val typeName: TypeName,
        val validator: String?,
        val message: String,
    )

    private class Parameter(
        val name: String,
        val typeName: TypeName,
    )

    private class ValueProperty(
        val name: String,
        val typeName: TypeName,
        val storageTypeName: TypeName,
        val initialExpression: String,
        val mapper: String?,
        val validator: String?,
        val validatorAcceptsNull: Boolean,
        val message: String,
    )

    private class ListProperty(
        val name: String,
        val elementTypeName: TypeName,
        val validator: String?,
        val message: String,
        val children: List<ChildSpec>,
    )

    /**
     * A DslBuilder interface used as a child: its generated builder, result
     * class, and required properties are resolved by name so that the parent
     * generated code can construct and build it.
     */
    private class ChildSpec(
        val functionName: String,
        val builderQualifiedName: String,
        val resultQualifiedName: String,
        val resultSupertype: KSType?,
        val specQualifiedName: String,
        val required: List<RequiredProperty>,
    )

    private class ChildScope(
        val name: String,
        val parameters: List<Parameter>,
        val child: ChildSpec,
    )

    private fun decapitalize(name: String): String =
        if (name.length >= 2 && name[1].isUpperCase()) name else name.replaceFirstChar { it.lowercase() }

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
        companion object {
            fun of(specName: String, annotation: KSAnnotation?): Names {
                val defaultResult = if (specName.endsWith("Dsl")) {
                    specName.removeSuffix("Dsl").takeIf { it.isNotEmpty() } ?: "${specName}Result"
                } else {
                    "${specName}Result"
                }
                val defaultBuilder = "${specName}Builder"
                val defaultFunction = "build${specName.removeSuffix("Dsl").takeIf { it.isNotEmpty() } ?: specName}"
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

        /**
         * Type annotations that are compiler markers rather than user-visible
         * annotations; KotlinPoet expresses their meaning through the type syntax
         * instead of copying them.
         */
        val IGNORED_ANNOTATIONS = setOf(
            "kotlin.ExtensionFunctionType",
            "kotlin.ParameterName",
            "kotlin.UnsafeVariance",
        )

        const val EXTENSION_FUNCTION_TYPE = "ExtensionFunctionType"
        const val PARAMETER_NAME = "ParameterName"

        val ANY: ClassName = Any::class.asClassName()
    }
}

private fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? =
    annotations.firstOrNull {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

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

private fun escapeStringLiteral(value: String): String = buildString {
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(character)
        }
    }
}

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
