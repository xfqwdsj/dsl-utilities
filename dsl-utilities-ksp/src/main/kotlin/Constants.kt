package top.ltfan.dslutilities.ksp

import com.squareup.kotlinpoet.*

internal const val DSL_BUILDER_ANNOTATION = "top.ltfan.dslutilities.DslBuilder"
internal const val DSL_VALUE_ANNOTATION = "top.ltfan.dslutilities.DslValue"
internal const val DSL_LIST_ANNOTATION = "top.ltfan.dslutilities.DslList"
internal const val DSL_CHILD_ANNOTATION = "top.ltfan.dslutilities.DslChild"
internal const val DSL_VALIDATOR_NAME = "top.ltfan.dslutilities.DslValidator"
internal const val DSL_MAPPER_NAME = "top.ltfan.dslutilities.DslMapper"

/** Prefix of the compiler-internal annotations that are never copied. */
internal const val KOTLIN_INTERNAL_PREFIX = "kotlin.internal."

/** Compiler marker annotations that KotlinPoet expresses through syntax. */
internal val EXTENSION_FUNCTION_TYPE = ClassName("kotlin", "ExtensionFunctionType")
internal val PARAMETER_NAME = ClassName("kotlin", "ParameterName")
internal val UNSAFE_VARIANCE = ClassName("kotlin", "UnsafeVariance")

/**
 * Type annotations that are compiler markers rather than user-visible
 * annotations; KotlinPoet expresses their meaning through the type syntax
 * instead of copying them.
 */
internal val IGNORED_ANNOTATIONS = setOf(
    EXTENSION_FUNCTION_TYPE,
    PARAMETER_NAME,
    UNSAFE_VARIANCE,
).mapTo(mutableSetOf()) { it.canonicalName }

/**
 * Names declared by generated function bodies and the generated builder;
 * the templates below use the same constants, so a reference that reuses
 * one of them receives an aliased import.
 */
internal const val VALUE_PARAMETER = "newValue"
internal const val STORED_VALUE = "stored"
internal const val SNAPSHOT = "snapshot"
internal const val ELEMENT = "element"
internal const val BUILD_FUNCTION = "build"
internal const val BLOCK_PARAMETER = "block"
internal const val BUILDER_LOCAL = "builder"
internal const val CHILD_BUILDER_LOCAL = "childBuilder"

/**
 * The names that every generated body and the builder declare and that
 * the templates emit as fixed text, registered per file up front. The
 * block, builder and child builder locals are allocated per function scope
 * instead, because their names depend on that function's parameter names.
 */
internal val GENERATED_NAMES = setOf(VALUE_PARAMETER, STORED_VALUE, SNAPSHOT, ELEMENT, BUILD_FUNCTION)

internal val REQUIRE = MemberName("kotlin", "require")
internal val REQUIRE_NOT_NULL = MemberName("kotlin", "requireNotNull")
internal val MUTABLE_LIST_OF = MemberName("kotlin.collections", "mutableListOf")
internal val TO_LIST = MemberName("kotlin.collections", "toList", isExtension = true)
