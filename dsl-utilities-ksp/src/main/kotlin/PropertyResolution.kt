package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*

internal fun ValueProperty.nameField(): String = fieldName

internal fun ListProperty.nameField(): String = fieldName

/**
 * Returns whether [declaration] or one of its supertypes declares an
 * abstract property named [name].
 */
internal fun requiresProperty(declaration: KSClassDeclaration, name: String): Boolean =
    (sequenceOf(declaration.asStarProjectedType()) + declaration.getAllSuperTypes())
        .mapNotNull { it.declaration as? KSClassDeclaration }
        .distinct()
        .any { current ->
            current.getDeclaredProperties().any { it.isAbstract() && it.simpleName.asString() == name }
        }

/**
 * Required properties are declared as `val`: they are parameters of the
 * generated builder and of the generated build function, so the compiler
 * enforces their presence.
 */
internal fun requiredProperty(
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
internal fun valueProperty(
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
internal fun DslProcessor.listProperty(
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
