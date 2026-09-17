package top.ltfan.dslutilities.ksp

import com.squareup.kotlinpoet.*

internal class ElementFunctions(
    val functions: List<FunSpec>,
    val shorthands: List<PropertySpec>,
)

/**
 * A class or object reference used as an expression: an object is
 * referenced by name, a class through its no-argument constructor.
 */
internal class Instantiation(
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
internal class FileContext(
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
