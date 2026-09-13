package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.*

object EvenIntValidator : DslValidator<Int> {
    override fun validate(value: Int): Boolean = value % 2 == 0
}

object NonBlankValidator : DslValidator<String> {
    override fun validate(value: String): Boolean = value.isNotBlank()
}

object PositiveIntValidator : DslValidator<Int> {
    override fun validate(value: Int): Boolean = value > 0
}

object SampleEventValidator : DslValidator<SampleEvent> {
    override fun validate(value: SampleEvent): Boolean = value !is TimedEvent || value.at >= 0
}

object IntStringMapper : DslMapper<Int, String> {
    override fun toStored(value: String): Int = value.toInt()

    override fun toValue(stored: Int): String = stored.toString()
}

abstract class BaseIntStringMapper : DslMapper<Int, String> {
    override fun toValue(stored: Int): String = stored.toString()
}

object GenericBaseMapper : BaseIntStringMapper() {
    override fun toStored(value: String): Int = value.toInt()
}

abstract class ReorderedBase<A, B> : DslMapper<B, A>

object ReorderedMapper : ReorderedBase<String, Int>() {
    override fun toStored(value: String): Int = value.toInt()

    override fun toValue(stored: Int): String = stored.toString()
}

object UpperCaseMapper : DslMapper<String, String> {
    override fun toStored(value: String): String = value.lowercase()

    override fun toValue(stored: String): String = stored.uppercase()
}

@DslBuilder(supertype = Named::class)
interface SampleDsl {
    val name: String

    @DslValue(validator = NonBlankValidator::class, message = "The title must not be blank.")
    val title: String

    @DslValue(initial = "1", mapper = IntStringMapper::class)
    var intToString: String

    @DslValue(initial = "2", mapper = GenericBaseMapper::class)
    var genericBaseToString: String

    @DslValue(initial = "3", mapper = ReorderedMapper::class)
    var reorderedToString: String

    @DslValue(
        initial = "2",
        mapper = IntStringMapper::class,
        validator = EvenIntValidator::class,
        message = "The value must be an even integer.",
    )
    var evenIntToString: String

    @DslValue
    var nickname: String?

    @DslValue(initial = "default", mapper = UpperCaseMapper::class)
    var prepared: String

    @DslList(validator = PositiveIntValidator::class, message = "The elements must be positive.")
    var numbers: MutableList<Int>
}

object NoNullStringValidator : DslValidator<String?> {
    override fun validate(value: String?): Boolean = value != null
}

// The generated builder copies this annotation verbatim; its parameter is
// exercised by the copy rather than by a runtime read.
@Suppress("unused")
@Target(AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class MaxBytes(val atMost: Byte)

interface Named {
    val name: String

    val title: String
}

interface BaseDsl {
    val extra: Int

    @DslValue(initial = "base")
    var base: String

    val defaulted: Int
        get() = 7
}

interface GenericBaseDsl<T> {
    val value: T
}

abstract class ListStoredBase<T> : DslMapper<List<T>, T> {
    override fun toValue(stored: List<T>): T = stored.first()
}

object NamesToListMapper : ListStoredBase<String>() {
    override fun toStored(value: String): List<String> = listOf(value)
}

@DslBuilder
interface InheritingDsl : BaseDsl {
    @DslValue(initial = "own")
    var own: String

    @DslValue(initial = "x", mapper = NamesToListMapper::class)
    var listStored: String

    @DslValue(initial = "0")
    var limited: @MaxBytes(2) String
}

@DslBuilder
interface GenericInheritingDsl : GenericBaseDsl<Int> {
    @DslValue
    var label: String?
}

class Containers {
    @DslBuilder
    interface NestedDsl {
        @DslValue(initial = "nested")
        var nested: String
    }
}

@DslBuilder
internal interface InternalDsl {
    @DslValue(initial = "hidden")
    var hidden: String
}

@DslBuilder
interface InvalidNullableDsl {
    @DslValue(validator = NoNullStringValidator::class)
    var nickname: String?
}

@DslBuilder
interface NullableDsl {
    @DslValue(initial = "fan", validator = NoNullStringValidator::class)
    var nickname: String?
}

@DslBuilder
interface Config {
    @DslValue(initial = "8080")
    var port: Int

    @DslValue
    var host: String?

    @DslList
    var tags: MutableList<String>
}

sealed interface SampleEvent

@DslBuilder(supertype = SampleEvent::class)
interface TransientEventDsl {
    @DslValue
    var intensity: Float?

    @DslValue
    var sharpness: Float?
}

@DslBuilder(supertype = SampleEvent::class)
interface ContinuousEventDsl {
    @DslValue
    var duration: Int?
}

@DslBuilder(supertype = SampleEvent::class)
interface TimedEventDsl {
    val at: Int

    @DslValue
    var label: String?
}

@DslBuilder
interface EventsDsl {
    @DslList(
        children = [TransientEventDsl::class, ContinuousEventDsl::class, TimedEventDsl::class],
        validator = SampleEventValidator::class,
        message = "The timed event positions must be non-negative.",
    )
    var events: MutableList<SampleEvent>

    @DslValue
    var title: String?
}

@DslBuilder
interface PatternDsl {
    @DslChild
    fun events(block: EventsDsl.() -> Unit = {})
}

@DslBuilder
interface HolderDsl {
    @DslChild
    fun event(at: Int, block: TimedEventDsl.() -> Unit = {})
}

@DslBuilder(
    resultName = "Palette",
    builderName = "PaletteAssembler",
    functionName = "palette",
)
interface PaletteDsl {
    @DslValue(initial = "black")
    var background: String
}

@DslBuilder(generateFunction = false)
interface GradientDsl {
    @DslValue(initial = "0")
    var from: Int

    @DslValue(initial = "255")
    var to: Int
}

object GradientFactory {
    fun gradient(block: GradientDsl.() -> Unit): Gradient {
        val builder = GradientDslBuilder()
        builder.block()
        return builder.build()
    }
}

@Target(AnnotationTarget.TYPE)
annotation class TypeMark

@DslBuilder
interface LambdaDsl {
    @DslValue
    var plain: (() -> Unit)?

    @DslValue
    var withReceiver: (String.() -> Unit)?

    @DslValue
    var withParameters: ((x: Int, y: String) -> Boolean)?

    @DslValue
    var marked: (@TypeMark () -> Unit)?

    @DslValue
    var markedWithReceiver: (@TypeMark String.() -> Unit)?

    @DslValue
    var suspending: (suspend () -> Unit)?
}

interface Defaulted {
    val label: String
        get() = "default"
}

@DslBuilder(supertype = Defaulted::class)
interface OverridingDsl {
    @DslValue(initial = "default")
    var label: String
}

@DslBuilder(supertype = Defaulted::class)
interface InheritingDefaultDsl

@DslBuilder
internal interface ShorthandDsl {
    @DslList(children = [TransientEventDsl::class])
    var items: MutableList<SampleEvent>
}

interface GenericChildBase<T> {
    @DslChild
    fun child(block: T.() -> Unit)
}

@DslBuilder
interface GenericChildScopeDsl : GenericChildBase<TransientEventDsl>

interface NullableGenericBase<T> {
    val nullableValue: T?
}

@DslBuilder
interface NullableGenericDsl : NullableGenericBase<String>

interface AbstractDefaultRoot {
    val inheritedLabel: String
}

interface ConcreteDefault : AbstractDefaultRoot {
    override val inheritedLabel: String
        get() = "inherited"
}

@DslBuilder
interface ConcreteShadowDsl : ConcreteDefault

@DslBuilder
interface SeparateListsDsl {
    @DslList(children = [TransientEventDsl::class])
    var transientEvents: MutableList<SampleEvent>

    @DslList(children = [ContinuousEventDsl::class])
    var continuousEvents: MutableList<SampleEvent>
}
