package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.*

/**
 * A member of a DslBuilder interface together with the type-parameter
 * environment of the class declaring it, so types declared against
 * generic bases resolve from the perspective of the analyzed class.
 */
internal class SubstitutedMember<T : KSDeclaration>(
    val declaration: T,
    val environment: Map<KSTypeParameter, KSType>,
)

/**
 * Walks the supertype hierarchy of a DslBuilder interface and collects the
 * abstract members the generated declarations must override. The nearest
 * declaration wins when a name is redeclared along the hierarchy; an
 * unresolvable supertype defers the spec to a later round.
 */
internal class Hierarchy(
    private val checker: Checker,
    private val reportedStarProjections: MutableSet<String>,
) {

    fun abstractFunctions(declaration: KSClassDeclaration): List<SubstitutedMember<KSFunctionDeclaration>> {
        val members = LinkedHashMap<String, SubstitutedMember<KSFunctionDeclaration>>()
        val seen = mutableSetOf<String>()
        walk(declaration, emptyMap()) { member, environment, _ ->
            if (member !is KSFunctionDeclaration) return@walk
            val signature = checker.functionSignature(member, environment)
            val candidate = SubstitutedMember(member, environment)
            val previous = members[signature]
            when {
                previous != null -> members[signature] = checker.moreSpecific(previous, candidate)
                !seen.add(signature) -> Unit
                member.isAbstract -> members[signature] = candidate
            }
        }
        return members.values.toList()
    }

    /**
     * Collects every function of the hierarchy, abstract or concrete, so
     * the caller can diagnose annotations placed on functions that declare
     * their own body. Sibling declarations of one signature merge into the
     * declaration with the most specific return type, which is the one an
     * implementing class must satisfy.
     */
    fun allFunctions(declaration: KSClassDeclaration): List<SubstitutedMember<KSFunctionDeclaration>> {
        val members = LinkedHashMap<String, SubstitutedMember<KSFunctionDeclaration>>()
        walk(declaration, emptyMap()) { member, environment, _ ->
            if (member !is KSFunctionDeclaration) return@walk
            val signature = checker.functionSignature(member, environment)
            val candidate = SubstitutedMember(member, environment)
            val previous = members[signature]
            members[signature] = previous?.let { checker.moreSpecific(it, candidate) } ?: candidate
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
 * Returns the declaration that stands for both [first] and [second] when
 * one signature is inherited more than once: the more specific return
 * type wins, and the nearer declaration wins when the return types denote
 * the same type. Kotlin rejects an inheritance whose return types are
 * unrelated, so that case keeps the first declaration.
 */
private fun Checker.moreSpecific(
    first: SubstitutedMember<KSFunctionDeclaration>,
    second: SubstitutedMember<KSFunctionDeclaration>,
): SubstitutedMember<KSFunctionDeclaration> {
    val firstType = first.declaration.returnType?.resolve()?.let { substituteType(it, first.environment) }
    val secondType = second.declaration.returnType?.resolve()?.let { substituteType(it, second.environment) }
    if (firstType == null || secondType == null || firstType.isError || secondType.isError) return first
    return when {
        secondType.isAssignableFrom(firstType) -> first
        firstType.isAssignableFrom(secondType) -> second
        else -> first
    }
}
