package top.ltfan.dslutilities

import kotlin.reflect.KClass

/**
 * Marks an interface as a DSL specification processed at compile time by
 * the dsl-utilities-ksp processor.
 *
 * For each interface annotated with [DslBuilder], the processor generates
 * a builder class, an immutable result data class, and a top-level
 * `build…` function:
 * - Immutable properties (`val`) are required values. They are constructor
 *   parameters of the generated builder and parameters of the generated
 *   `build…` function, so the compiler enforces their presence at every
 *   call site.
 * - Mutable properties (`var`) annotated with [DslValue] or [DslList] are
 *   set inside the DSL block. A nullable property is optional and defaults
 *   to `null`; a non-nullable property defaults to the [DslValue.initial]
 *   constant.
 * - Validation expressions are emitted inline into the generated accessors
 *   and into the generated `build` function, with property names as
 *   compile-time string constants.
 * - The generated result class exposes read-only properties, so the built
 *   value is locked against modification by the type system.
 *
 * The generated result class is named after the annotated interface
 * with a trailing `Dsl` removed, or with `Result` appended when the
 * interface name has no trailing `Dsl`. The builder class is named
 * after the annotated interface with `Builder` appended, and the
 * build function is named `build` followed by the result class name.
 * [resultName], [builderName], and [functionName] override these names,
 * and [generateFunction] controls whether the build function is generated.
 *
 * @param supertype The non-generic interface implemented by the generated
 *   result class. Declaring a supertype lets several result classes be
 *   collected in one list of the supertype, for example as the element
 *   type of a [DslList] property whose [DslList.children] build those
 *   result classes. When unset, the result class implements no additional
 *   interface.
 * @param resultName The name of the generated result class. When empty,
 *   the name is derived from the annotated interface.
 * @param builderName The name of the generated builder class. When empty,
 *   the name is derived from the annotated interface.
 * @param functionName The name of the generated build function. When
 *   empty, the name is derived from the annotated interface.
 * @param generateFunction Whether to generate the build function. A
 *   library that exposes its own entry point sets this to `false` and uses
 *   the generated builder class directly.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class DslBuilder(
    val supertype: KClass<*> = Unit::class,
    val resultName: String = "",
    val builderName: String = "",
    val functionName: String = "",
    val generateFunction: Boolean = true,
)

/**
 * Declares a value property of a [DslBuilder] interface.
 *
 * @param initial The initial value as a compile-time constant written in
 *   string form, parsed at compile time according to the property type.
 *   Supported types are [Byte], [Short], [Int], [Long], [UByte], [UShort],
 *   [UInt], [ULong], [Float], [Double], [Boolean], [Char], and [String].
 *   An empty string means the property has no initial value.
 * @param validator A [DslValidator] object or class. The generated setter
 *   calls `require` with this validator before storing the value. When
 *   unset, the property accepts every value.
 * @param mapper A [DslMapper] object or class defining how values of the
 *   declared property type map to and from the stored representation. When
 *   unset, the property stores the declared type directly. A nullable
 *   property with a mapper requires an [initial] value.
 * @param message The message passed to `require` when validation fails.
 *   When empty, a message containing the property name is generated.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class DslValue(
    val initial: String = "",
    val validator: KClass<*> = Unit::class,
    val mapper: KClass<*> = Unit::class,
    val message: String = "",
)

/**
 * Declares a list property of a [DslBuilder] interface. The property type
 * is `MutableList` of the element type, and the elements are mutated
 * directly inside the DSL block. The generated result class exposes the
 * list as a read-only `List`.
 *
 * @param children DslBuilder interfaces that become element functions of
 *   the generated builder. Each child receives a function named after the
 *   child, whose parameters carry the required properties of the child and
 *   whose optional trailing block configures the child; the function
 *   builds the child value and adds it to the list. A child whose
 *   properties all have values also receives a property shorthand that
 *   adds a child built with the default configuration.
 * @param validator A [DslValidator] object or class applied to each
 *   element in the generated `build` function. When unset, every element
 *   is accepted.
 * @param message The message passed to `require` when element validation
 *   fails. When empty, a message containing the property name is
 *   generated.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class DslList(
    val validator: KClass<*> = Unit::class,
    val children: Array<KClass<*>> = [],
    val message: String = "",
)

/**
 * Declares a function of a [DslBuilder] interface as a child scope. The
 * function declares a single trailing parameter whose type is a function
 * with the child [DslBuilder] interface as receiver, followed by any
 * parameters carrying the required properties of the child; the processor
 * generates the function body, which builds the child value and stores it.
 * The child value is required in the generated `build` function, so the
 * child scope function is invoked before building.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class DslChild
