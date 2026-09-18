package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

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
    internal val codeGenerator: CodeGenerator,
    internal val logger: KSPLogger,
) : SymbolProcessor {

    internal val generatedTypeOwners = mutableMapOf<String, String>()

    /**
     * Generated type names by lowercase spelling, so case-only conflicts are
     * visible.
     */
    internal val generatedTypeSpellings = mutableMapOf<String, String>()
    internal val generatedFunctionOwners = mutableMapOf<String, MutableList<Pair<String, GeneratedSignature>>>()
    internal val generatedPropertyOwners = mutableMapOf<String, String>()
    internal val reportedStarProjections = mutableSetOf<String>()
    internal var packageDeclarations: MutableMap<String, List<KSDeclaration>>? = null

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
}

/** Provides [DslProcessor] to the KSP runtime through the service loader. */
class DslProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): DslProcessor =
        DslProcessor(environment.codeGenerator, environment.logger)
}
