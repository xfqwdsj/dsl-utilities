package top.ltfan.dslutilities.ksp

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/**
 * Builds the generated builder class: the required properties arrive
 * as constructor parameters, the mutable members are stored in private
 * fields, and the spec members are overridden with the validation and
 * mapping logic wired inline into the accessors.
 */
internal fun builderType(
    specTypeName: ClassName,
    builderTypeName: ClassName,
    resultTypeName: ClassName,
    visibility: List<KModifier>,
    memberVisibility: KModifier,
    requiredProperties: List<RequiredProperty>,
    valueProperties: List<ValueProperty>,
    listProperties: List<ListProperty>,
    childScopes: List<ChildScope>,
    buildHook: Instantiation?,
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
        val initialValue = property.initialProvider?.let {
            CodeBlock.of("%L.provide()", it.code(context))
        } ?: property.initialValue ?: CodeBlock.of("null")
        val initializer = if (property.mapper != null && !property.required) {
            CodeBlock.of("%L.toStored(%L)", property.mapper.code(context), initialValue)
        } else {
            initialValue
        }
        builder.addProperty(
            PropertySpec.builder(
                property.nameField(),
                if (property.required) property.storageTypeName.copy(nullable = true) else property.storageTypeName,
                KModifier.PRIVATE,
            )
                .mutable(true)
                .initializer(initializer)
                .build()
        )
        if (property.required) {
            builder.addProperty(
                PropertySpec.builder(property.isSetFieldName, BOOLEAN, KModifier.PRIVATE)
                    .mutable(true).initializer("false").build()
            )
        }
    }
    for (property in listProperties) {
        val initializer = property.hook?.let {
            CodeBlock.of("%L(%L)", context.expression(HOOKED_DSL_LIST), it.code(context))
        } ?: CodeBlock.of("%L()", context.member(MUTABLE_LIST_OF))
        builder.addProperty(
            PropertySpec.builder(
                property.nameField(),
                if (property.hook == null) MUTABLE_LIST.parameterizedBy(property.elementTypeName)
                else HOOKED_DSL_LIST.parameterizedBy(property.elementTypeName),
                KModifier.PRIVATE,
            )
                .initializer(initializer)
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
        if (property.setterName != null) {
            val parameterName = requireNotNull(property.setterParameterName)
            builder.addFunction(
                FunSpec.builder(property.setterName)
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter(parameterName, property.typeName)
                    .addStatement("%N = %N", property.nameField(), parameterName)
                    .build()
            )
            continue
        }
        val getter = FunSpec.getterBuilder()
            .apply {
                if (property.hook != null) {
                    addStatement("%L.beforeAccess()", property.hook.code(context))
                }
                if (property.required) {
                    addStatement(
                        "%L(%N) { %S }",
                        context.member(CHECK),
                        property.isSetFieldName,
                        "Property ${property.name} is required.",
                    )
                }
                if (property.mapper == null) {
                    if (property.required && !property.typeName.isNullable) addStatement(
                        "return %N as %T",
                        property.nameField(),
                        property.typeName
                    )
                    else addStatement("return %N", property.nameField())
                } else {
                    if (property.required) addStatement(
                        "return %L.toValue(%N as %T)",
                        property.mapper.code(context), property.nameField(), property.storageTypeName,
                    ) else addStatement("return %L.toValue(%N)", property.mapper.code(context), property.nameField())
                }
            }
            .build()
        val setter = FunSpec.setterBuilder()
            .addParameter(VALUE_PARAMETER, property.typeName)
            .apply {
                if (property.hook != null) {
                    addStatement("%L.beforeSet($VALUE_PARAMETER)", property.hook.code(context))
                }
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
                if (property.required) addStatement("%N = true", property.isSetFieldName)
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
        if (property.validator != null && !property.required) {
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
        if (property.appendName != null) {
            val parameterName = requireNotNull(property.appendParameterName)
            builder.addFunction(
                FunSpec.builder(property.appendName)
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter(parameterName, property.elementTypeName)
                    .addStatement("%N.add(%N)", property.nameField(), parameterName)
                    .build()
            )
            continue
        }
        val setter = FunSpec.setterBuilder()
            .addParameter(VALUE_PARAMETER, property.typeName)
            .addStatement("val $SNAPSHOT = $VALUE_PARAMETER.%L()", context.member(TO_LIST))
            .apply {
                if (property.hook == null) {
                    addStatement("%N.clear()", property.nameField())
                    addStatement("%N.addAll($SNAPSHOT)", property.nameField())
                } else {
                    addStatement("%N.replaceWith($SNAPSHOT)", property.nameField())
                }
            }
            .build()
        builder.addProperty(
            PropertySpec.builder(
                property.name,
                property.typeName,
                KModifier.OVERRIDE
            )
                .mutable(true)
                .getter(FunSpec.getterBuilder().apply {
                    if (property.hook != null) addStatement("%L.beforeAccess()", property.hook.code(context))
                    addStatement("return %N", property.nameField())
                }.build())
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
    if (buildHook != null) buildCode.addStatement("%L.beforeBuild(this)", buildHook.code(context))
    for (property in valueProperties) {
        if (property.required) buildCode.addStatement(
            "%L(%N) { %S }",
            context.member(CHECK),
            property.isSetFieldName,
            "Property ${property.name} is required.",
        )
    }
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
    for (property in valueProperties) {
        val source = if (property.setterName == null) property.name else property.nameField()
        addArgument(property.name, "%N", source)
    }
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
internal fun resultType(
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
 * the property shorthands of children that declare no required properties
 * and no `@DslChild` functions.
 */
internal fun elementFunctions(
    specTypeName: ClassName,
    parentVisibility: KModifier,
    listProperties: List<ListProperty>,
    context: FileContext,
): ElementFunctions {
    val functions = mutableListOf<FunSpec>()
    val shorthands = mutableListOf<PropertySpec>()
    for (property in listProperties) {
        val receiver = property.scopeTypeName ?: specTypeName
        if (property.scopeTypeName != null) {
            functions += FunSpec.builder(property.scopeName)
                .addModifiers(parentVisibility, KModifier.INLINE)
                .receiver(specTypeName)
                .addParameter(
                    BLOCK_PARAMETER,
                    LambdaTypeName.get(receiver = receiver, returnType = UNIT),
                )
                .addStatement("%L(this.%N).%N()", context.expression(receiver), property.name, BLOCK_PARAMETER)
                .build()
        }
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
                .receiver(receiver)
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
                .apply {
                    if (property.scopeTypeName == null) {
                        addStatement("this.%N.add(%N.$BUILD_FUNCTION())", property.name, childBuilderName)
                    } else {
                        addStatement("this.elements.add(%N.$BUILD_FUNCTION())", childBuilderName)
                    }
                }
                .build()
            if (child.required.isEmpty() && !child.requiresConfiguration) {
                shorthands += PropertySpec.builder(child.functionName, UNIT)
                    .addModifiers(visibility)
                    .receiver(receiver)
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

/** One generated receiver type per named list, even when children repeat. */
internal fun namedListScopes(
    visibility: KModifier,
    listProperties: List<ListProperty>,
): List<TypeSpec> = listProperties.mapNotNull { property ->
    val typeName = property.scopeTypeName ?: return@mapNotNull null
    TypeSpec.classBuilder(typeName)
        .addModifiers(visibility)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("elements", MUTABLE_LIST.parameterizedBy(property.elementTypeName))
                .build(),
        )
        .addSuperinterface(DSL_LIST_SCOPE.parameterizedBy(property.elementTypeName))
        .addProperty(
            PropertySpec.builder("elements", MUTABLE_LIST.parameterizedBy(property.elementTypeName), KModifier.OVERRIDE)
                .initializer("elements")
                .build(),
        )
        .build()
}

/**
 * Builds the top-level entry point that creates the builder, runs the DSL
 * block and returns the built result.
 */
internal fun buildFunction(
    names: Names,
    specTypeName: ClassName,
    visibility: KModifier,
    builderTypeName: ClassName,
    resultTypeName: ClassName,
    requiredProperties: List<RequiredProperty>,
    requireConfiguration: Boolean,
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
                .apply { if (!requireConfiguration) defaultValue("{}") }
                .build()
        )
        .returns(resultTypeName)
        .addStatement("val %N = %T(%L)", builderName, builderTypeName, arguments)
        .addStatement("%N.invoke(%N)", blockName, builderName)
        .addStatement("return %N.$BUILD_FUNCTION()", builderName)
        .build()
}

/**
 * Builds the parameter of a required property for the generated inline
 * entry points. A function-typed parameter cannot be passed to the
 * non-inline builder call from an inline function, so it is `noinline`.
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

/**
 * Returns a copy of this type with the nullable marker set; every other
 * property is preserved.
 */
private fun TypeName.asNullable(): TypeName = copy(nullable = true)
