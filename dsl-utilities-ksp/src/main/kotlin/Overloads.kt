package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

/**
 * Reports generated declaration collisions before opening an output file,
 * keeping invalid custom or derived names as KSP diagnostics instead of
 * file-creation failures or later redeclaration errors. Declarations
 * already present in the specification's package are reserved as well,
 * because generated functions and extension properties share the
 * package-level scope with them.
 */
internal fun DslProcessor.reserveGeneratedNames(
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
internal fun sameSignature(
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
 * Returns `true` when [function] declares the same Kotlin signature
 * as a generated [signature], so emitting the generated declaration
 * would produce conflicting overloads. Kotlin compares names and
 * parameter types; an extension receiver counts as the first parameter.
 */
internal fun matchesSignature(
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
internal fun DslProcessor.declarationsInPackage(resolver: Resolver, packageName: String): List<KSDeclaration> {
    val cache = packageDeclarations ?: mutableMapOf<String, List<KSDeclaration>>().also {
        packageDeclarations = it
    }
    return cache.getOrPut(packageName) { resolver.getDeclarationsFromPackage(packageName).toList() }
}
