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

interface GenericChildReturnBase<R> {
    @DslChild
    fun child(block: TransientEventDsl.() -> Unit): R
}

@DslBuilder
interface GenericUnitChildReturnDsl : GenericChildReturnBase<Unit>

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

@DslBuilder
interface ReservedNamesDsl {
    val `when`: String

    val block: String

    val builder: String
}

interface CovariantName {
    val name: CharSequence
}

@DslBuilder(supertype = CovariantName::class)
interface CovariantResultDsl {
    val name: String
}

interface NullableAnyValue {
    val value: Any?
}

@DslBuilder(supertype = NullableAnyValue::class)
interface NullableStringValueDsl {
    val value: String?
}

interface BlockItem

@DslBuilder(supertype = BlockItem::class)
interface BlockChildDsl {
    val block: String
}

@DslBuilder
interface BlockListDsl {
    @DslList(children = [BlockChildDsl::class])
    var items: MutableList<BlockItem>
}

@DslBuilder
interface BlockScopeHolderDsl {
    @DslChild
    fun child(block: String, configure: BlockChildDsl.() -> Unit = {})
}

@DslBuilder
internal interface InternalListChildDsl {
    @DslValue(initial = "internal")
    var value: String
}

@DslBuilder
interface PublicInternalChildListDsl {
    @DslList(children = [InternalListChildDsl::class])
    var items: MutableList<Any>
}

internal interface InternalChildResult {
    val value: String
}

@DslBuilder(supertype = InternalChildResult::class)
interface PublicInternalResultChildDsl {
    @DslValue(initial = "internal-result")
    var value: String
}

@DslBuilder
interface PublicInternalResultListDsl {
    @DslList(children = [PublicInternalResultChildDsl::class])
    var items: MutableList<Any>
}

@DslBuilder
interface PublicInternalResultScopeDsl {
    @DslChild
    fun child(block: PublicInternalResultChildDsl.() -> Unit = {})
}

interface WideDiamondProperty {
    val value: CharSequence
}

interface NarrowDiamondProperty {
    val value: String
}

@DslBuilder
interface DiamondPropertyDsl : WideDiamondProperty, NarrowDiamondProperty

@DslBuilder
interface RequiredNestedChildDsl {
    @DslChild
    fun event(block: TransientEventDsl.() -> Unit = {})
}

@DslBuilder
interface RequiredNestedListDsl {
    @DslList(children = [RequiredNestedChildDsl::class])
    var items: MutableList<Any>
}

@DslBuilder
interface BackingNamesDsl {
    @DslValue(initial = "name")
    var name: String

    @DslValue(initial = "field")
    var nameField: String

    @DslList
    var items: MutableList<String>

    @DslValue(initial = "items")
    var itemsField: String

    @DslValue(initial = "child")
    var childField: String

    @DslChild
    fun child(block: TransientEventDsl.() -> Unit = {})
}

class DefaultArgumentValidator(private val expected: String = "valid") : DslValidator<String> {
    override fun validate(value: String): Boolean = value == expected
}

class SecondaryConstructorValidator private constructor(private val expected: String) : DslValidator<String> {
    constructor() : this("valid-secondary")

    override fun validate(value: String): Boolean = value == expected
}

@DslBuilder
interface ConstructorValidatorDsl {
    @DslValue(initial = "valid", validator = DefaultArgumentValidator::class)
    var value: String

    @DslValue(initial = "valid-secondary", validator = SecondaryConstructorValidator::class)
    var secondary: String
}

interface ConcreteBackingName {
    val nameField: String
        get() = "default"
}

@DslBuilder
interface ConcreteBackingDsl : ConcreteBackingName {
    @DslValue(initial = "name")
    var name: String
}

typealias AliasedTransientBlock = TransientEventDsl.() -> Unit
typealias AliasedUnit = Unit
typealias AliasedMarkedString = String
typealias AliasedIntList = MutableList<Int>
typealias AliasedValidator = NonBlankValidator
typealias AliasedBase = BaseDsl

@DslBuilder
interface AliasedChildScopeDsl {
    @DslChild
    fun aliasedChild(block: AliasedTransientBlock)

    @DslChild
    fun aliasedUnitChild(block: TransientEventDsl.() -> AliasedUnit = {})
}

@DslBuilder
interface AliasedAnnotationDsl {
    val marked: @MaxBytes(3) AliasedMarkedString
}

@DslBuilder
interface AliasedListDsl {
    @DslList
    var values: AliasedIntList
}

@DslBuilder
interface AliasedValueDsl {
    @DslValue(initial = "alias", validator = AliasedValidator::class)
    var value: AliasedMarkedString
}

@DslBuilder
interface AliasedInheritanceDsl : AliasedBase {
    @DslValue(initial = "alias-own")
    var aliasOwn: String
}

@DslBuilder
interface OverloadSpecDsl

fun buildOverloadSpec(block: (Int) -> Unit) {
    block(0)
}

interface EventContainer {
    val child: SampleEvent
}

@DslBuilder(supertype = EventContainer::class, resultName = "EventContainerResult")
interface EventContainerDsl {
    @DslChild
    fun child(block: TransientEventDsl.() -> Unit = {})
}

typealias AliasedGenericChildBlock<R> = TransientEventDsl.() -> R

@DslBuilder
interface GenericAliasedChildScopeDsl {
    @DslChild
    fun genericAliasedChild(block: AliasedGenericChildBlock<Unit>)
}

@DslBuilder
interface PlainChildDsl {
    @DslValue(initial = "plain")
    var value: String
}

interface AnyChildHolder {
    val child: Any
}

@DslBuilder(supertype = AnyChildHolder::class, resultName = "AnyChildHolderResult")
interface AnyChildHolderDsl {
    @DslChild
    fun child(block: PlainChildDsl.() -> Unit = {})
}

@DslBuilder
interface NullableBlockSpecDsl

@Suppress("UNUSED_PARAMETER")
fun buildNullableBlockSpec(block: (NullableBlockSpecDsl.() -> Unit)?) {
}

typealias MarkedIntListAlias<T> = @MaxBytes(5) MutableList<T>

@DslBuilder
interface AliasedMarkedIntsDsl {
    @DslList
    var values: MarkedIntListAlias<Int>
}

typealias AliasAny = Any

interface AliasAnyHolder {
    val child: AliasAny
}

@DslBuilder(supertype = AliasAnyHolder::class, resultName = "AliasAnyHolderResult")
interface AliasAnyHolderDsl {
    @DslChild
    fun child(block: PlainChildDsl.() -> Unit = {})
}

interface BareChildParameterBase<B> {
    @DslChild
    fun child(block: B)
}

@DslBuilder
interface BareChildParameterDsl : BareChildParameterBase<TransientEventDsl.() -> Unit>

typealias DirectChildBlockAlias = TransientEventDsl.() -> Unit

@DslBuilder
interface AliasedBareChildParameterDsl : BareChildParameterBase<DirectChildBlockAlias>

typealias StarListAlias<T> = List<T>?

@DslBuilder
interface AliasedStarListDsl {
    @DslValue
    var values: StarListAlias<*>
}

interface NullableAliasAnyHolder {
    val child: AliasAny?
}

@DslBuilder(supertype = NullableAliasAnyHolder::class, resultName = "NullableAliasAnyHolderResult")
interface NullableAliasAnyHolderDsl {
    @DslChild
    fun child(block: PlainChildDsl.() -> Unit = {})
}

@Suppress("unused")
typealias PhantomChildBlock<Marker, B> = B.() -> Unit

@DslBuilder
interface PhantomChildScopeDsl {
    @DslChild
    fun child(block: PhantomChildBlock<*, PlainChildDsl> = {})
}

@Suppress("unused")
typealias GenericAliasAny<T> = Any

interface GenericAliasAnyHolder {
    val child: GenericAliasAny<*>
}

@DslBuilder(supertype = GenericAliasAnyHolder::class, resultName = "GenericAliasAnyHolderResult")
interface GenericAliasAnyHolderDsl {
    @DslChild
    fun child(block: PlainChildDsl.() -> Unit = {})
}

private typealias PrivatePlainAlias = String

@DslBuilder
interface PrivateAliasDsl {
    val value: PrivatePlainAlias
}

typealias AliasedParamChildBlock<T> = T.() -> Unit

interface AliasedParamChildBase<T> {
    @DslChild
    fun aliasedParamChild(block: AliasedParamChildBlock<T>)
}

@DslBuilder
interface AliasedParamChildScopeDsl : AliasedParamChildBase<TransientEventDsl>

interface AliasedValueBase<T> {
    val value: T
}

typealias AliasedValueBaseAlias<T> = AliasedValueBase<T>

interface AliasedValueMiddle<T> : AliasedValueBaseAlias<T>

@DslBuilder
interface AliasedValueMidDsl : AliasedValueMiddle<String> {
    @DslValue(initial = "own")
    var own: String
}

typealias AliasedMapperBase<A, B> = DslMapper<A, B>

abstract class AliasedMapperMiddle<A, B> : AliasedMapperBase<A, B>

object AliasedMapperImpl : AliasedMapperMiddle<Int, String>() {
    override fun toStored(value: String): Int = value.toInt()

    override fun toValue(stored: Int): String = stored.toString()
}

@DslBuilder
interface AliasedMapperDsl {
    @DslValue(initial = "7", mapper = AliasedMapperImpl::class)
    var value: String
}

@DslBuilder
interface GenericArgumentAnnotationDsl {
    val value: List<@MaxBytes(3) String>?

    val nested: Map<String, List<@MaxBytes(4) Int>>
}

typealias CarriedArgumentAnnotationAlias<T> = Map<String, List<@MaxBytes(6) T>>

@DslBuilder
interface CarriedArgumentAnnotationDsl {
    val value: CarriedArgumentAnnotationAlias<Int>
}

typealias CarriedFunctionAnnotationAlias<T> = (@MaxBytes(7) T) -> Unit

@DslBuilder
interface CarriedFunctionAnnotationDsl {
    val callback: CarriedFunctionAnnotationAlias<String>
}

typealias MarkedKeepAlias<T> = @MaxBytes(40) MutableList<@MaxBytes(41) T>

@DslBuilder
interface MarkedKeepDsl {
    val items: MarkedKeepAlias<*>
}

typealias WrappedAlias<T> = List<@MaxBytes(20) T>

@DslBuilder
interface WrappedDsl {
    val value: List<WrappedAlias<Int>>
}

typealias LinkedAnnotationInner<T> = List<T>

typealias LinkedAnnotationOuter<T> = LinkedAnnotationInner<@MaxBytes(43) T>

@DslBuilder
interface LinkedAnnotationDsl {
    val value: LinkedAnnotationOuter<Int>
}

typealias MidTargetAnnotation<T> = @MaxBytes(72) List<T>

typealias TopTargetAnnotationLink<T> = @TypeMark MidTargetAnnotation<T>

@DslBuilder
interface TopTargetAnnotationDsl {
    val value: TopTargetAnnotationLink<Int>
}

@Repeatable
@Target(AnnotationTarget.TYPE)
annotation class RepeatTypeMark(val value: Int)

typealias RepeatedAnnotationAlias<T> = @RepeatTypeMark(5) @RepeatTypeMark(5) List<T>

@DslBuilder
interface RepeatedAnnotationDsl {
    val value: RepeatedAnnotationAlias<Int>
}

@DslBuilder
interface NestedWrappedDsl {
    val map: Map<String, WrappedAlias<Int>>

    val triple: List<List<WrappedAlias<Int>>>
}

interface InheritedAnnotationBase<T> {
    val annotated: List<@MaxBytes(95) T>
}

@DslBuilder
interface InheritedAnnotationDsl : InheritedAnnotationBase<String> {
    @DslValue(initial = "x")
    var own: String
}

@DslBuilder
interface AnnotatedParamChildDsl {
    val values: List<String>
}

interface AnnotatedParamChildBase<T> {
    @DslChild
    fun child(values: List<@MaxBytes(90) T>, block: AnnotatedParamChildDsl.() -> Unit = {})
}

@DslBuilder
interface AnnotatedParamScopeDsl : AnnotatedParamChildBase<String>

typealias AnnotatedChildBlock = @MaxBytes(91) AnnotatedParamChildDsl.() -> Unit

@DslBuilder
interface AnnotatedBlockScopeDsl {
    @DslChild
    fun child(values: List<String>, block: AnnotatedChildBlock = {})
}

typealias AnnotatedGenericChildBlock<T> = @MaxBytes(92) T.() -> Unit

interface AnnotatedGenericChildBase<T> {
    @DslChild
    fun child(block: AnnotatedGenericChildBlock<T>)
}

@DslBuilder
interface AnnotatedGenericChildScopeDsl : AnnotatedGenericChildBase<TransientEventDsl>

@DslBuilder
interface FunctionRequiredDsl {
    val callback: (String) -> Unit

    @DslValue(initial = "x")
    var value: String
}

@DslBuilder
interface NullableFunctionRequiredDsl {
    val callback: (() -> Unit)?
}

@DslBuilder
interface FunctionChildDsl {
    val callback: (String) -> Unit
}

@DslBuilder
interface FunctionListHolderDsl {
    @DslList(children = [FunctionChildDsl::class])
    var items: MutableList<Any>
}

@DslBuilder
interface ShadowingBlockChildDsl

@DslBuilder
interface ShadowingBlockNameDsl {
    @DslChild
    fun child(childField: ShadowingBlockChildDsl.() -> Unit)
}

@DslBuilder
interface ShadowingValueChildDsl {
    val childField: Int
}

@DslBuilder
interface ShadowingValueNameDsl {
    @DslChild
    fun child(childField: Int, block: ShadowingValueChildDsl.() -> Unit)
}

@DslBuilder
interface ShadowedListChildDsl {
    val items: MutableList<Any>
}

@DslBuilder
interface ShadowedListHolderDsl {
    @DslList(children = [ShadowedListChildDsl::class])
    var items: MutableList<Any>
}

@DslBuilder
interface BlockNamedListChildDsl

@DslBuilder
interface BlockNamedListHolderDsl {
    @DslList(children = [BlockNamedListChildDsl::class])
    var block: MutableList<Any>
}

@DslBuilder
interface EntryBlockChildDsl {
    @DslValue(initial = "false")
    var touched: Boolean
}

@DslBuilder
interface EntryBlockCaptureDsl {
    @DslChild
    fun block(block: EntryBlockChildDsl.() -> Unit = {})
}

@Suppress("ClassName")
object fieldValidator : DslValidator<Int> {
    override fun validate(value: Int) = value >= 0
}

@DslBuilder
interface LowercaseValidatorDsl {
    @DslValue(initial = "0", validator = fieldValidator::class)
    var value: Int
}

@Suppress("ClassName")
object newValue : DslValidator<Int> {
    override fun validate(value: Int) = value >= 0
}

@DslBuilder
interface ShadowedValidatorDsl {
    @DslValue(initial = "0", validator = newValue::class)
    var value: Int
}

@Suppress("ClassName")
@DslBuilder
interface lowercaseSpecDsl {
    @DslValue(initial = "0")
    var value: Int
}

@Suppress("ClassName")
object `when` : DslValidator<Int> {
    override fun validate(value: Int) = value >= 0
}

@DslBuilder
interface KeywordValidatorDsl {
    @DslValue(initial = "0", validator = `when`::class)
    var value: Int
}

@DslBuilder
interface ShadowedRequireDsl {
    fun require(value: Boolean, lazyMessage: () -> Any) {}

    @DslValue(initial = "0", validator = fieldValidator::class)
    var value: Int
}

@DslBuilder
interface ShadowedRequireNotNullChildDsl

@DslBuilder
interface ShadowedRequireNotNullDsl {
    fun <T> requireNotNull(value: T?, lazyMessage: () -> Any): T = value ?: error("shadowed")

    @DslChild
    fun child(block: ShadowedRequireNotNullChildDsl.() -> Unit = {})
}

@DslBuilder
interface ShadowedListDsl {
    fun <T> mutableListOf(): MutableList<T> = error("shadowed")

    fun List<Any>.toList(): List<Any> = emptyList()

    @DslList
    var items: MutableList<Any>
}

@Suppress("unused")
@DslBuilder
interface HarmlessApplyChildDsl {
    val apply: Int

    fun apply() {}

    fun apply(value: Int) {}
}

@DslBuilder
interface HarmlessApplyParentDsl {
    @DslChild
    fun child(apply: Int, block: HarmlessApplyChildDsl.() -> Unit)
}

@Suppress("ClassName")
class nestedContainer {
    @Suppress("ClassName")
    @DslBuilder
    interface nestedSpecDsl {
        @DslValue(initial = "nested")
        var nested: String
    }
}

@Suppress("ClassName")
class build : DslMapper<Int, String> {
    override fun toStored(value: String): Int = value.toInt()

    override fun toValue(stored: Int): String = stored.toString()
}

@DslBuilder
interface BuildMapperNameDsl {
    @DslValue(initial = "1", mapper = build::class)
    var value: String
}

@Suppress("ClassName")
class element {
    object Validator : DslValidator<Int> {
        override fun validate(value: Int) = value >= 0
    }
}

@DslBuilder
interface NestedValidatorShadowDsl {
    @DslList(validator = element.Validator::class)
    var values: MutableList<Int>
}

@Suppress("unused", "ClassName")
class newValueRef

@Suppress("unused", "ClassName")
class newValueRef_

@Target(AnnotationTarget.TYPE)
annotation class ExtensionFunctionType

@DslBuilder
interface MarkerNameCollisionDsl {
    val callback: @ExtensionFunctionType (String) -> Unit
}

@Target(AnnotationTarget.TYPE)
annotation class ParameterName(val name: String)

@DslBuilder
interface ParameterNameCollisionDsl {
    val callback: (@ParameterName("renamed") String) -> Unit
}

@DslBuilder
interface NestedFunctionReceiverDsl {
    val callback: (@MaxBytes(93) PlainChildDsl.() -> Unit).() -> Unit
}

@DslBuilder
interface NullableNestedFunctionReceiverDsl {
    val callback: (PlainChildDsl.() -> Unit)?.() -> Unit
}

@DslBuilder
interface EmptyInitialDsl {
    @DslValue(initial = "")
    var text: String

    @DslValue(initial = "")
    var nullable: String?
}

/**
 * Initial values that a generated UTF-8 file cannot carry verbatim:
 * control characters, an unpaired surrogate, invisible separators and
 * format characters, unassigned and private-use code points, and a string
 * that contains an unpaired surrogate.
 */
@DslBuilder
interface EscapedCharInitialDsl {
    @DslValue(initial = "\u0000")
    var nul: Char

    @DslValue(initial = "\u0008")
    var backspace: Char

    @DslValue(initial = "\u007f")
    var delete: Char

    @DslValue(initial = "\u2028")
    var lineSeparator: Char

    @DslValue(initial = "\u2029")
    var paragraphSeparator: Char

    @DslValue(initial = "\ufeff")
    var format: Char

    @DslValue(initial = "\ue000")
    var privateUse: Char

    @DslValue(initial = "\u0378")
    var unassigned: Char

    @DslValue(initial = "\ud800")
    var surrogate: Char

    @DslValue(initial = "lone \ud800 surrogate")
    var text: String
}

@DslBuilder
interface FloatingPointInitialDsl {
    @DslValue(initial = "0.1")
    var ratio: Float

    @DslValue(initial = "1e30")
    var huge: Float

    @DslValue(initial = "1e-320")
    var tiny: Double
}

/** Messages that carry an unpaired surrogate, rendered through `require`. */
@DslBuilder
interface EscapedMessageDsl {
    @DslValue(validator = NonBlankValidator::class, message = "value \ud800 message")
    val value: String

    @DslList(validator = NonBlankValidator::class, message = "list \ud800 message")
    var items: MutableList<String>
}

/**
 * Shadows the standard library function of the same name for declarations
 * of this package, so generated code has to bind the standard library
 * function through its explicit import. Tests in this package must import
 * [kotlin.require] explicitly before calling it.
 */
@Suppress("unused")
fun require(value: Boolean, lazyMessage: () -> Any) {
    error("The package-level require must not be reached from generated code.")
}

@DslBuilder
interface NamedChildDsl {
    @DslValue(initial = "0")
    var value: Int
}

@DslBuilder
interface CaptureChildBuilderDsl {
    @DslChild
    fun child(block: NamedChildDsl.() -> Unit = {})

    @Suppress("TestFunctionName")
    @DslChild
    fun NamedChildDslBuilder(block: NamedChildDsl.() -> Unit = {})
}

@DslBuilder
interface MultilineInitialDsl {
    @DslValue(initial = "first\nsecond")
    var text: String
}

@DslBuilder
interface NegativeBoundaryInitialDsl {
    @DslValue(initial = "-32768")
    var short: Short

    @DslValue(initial = "-128")
    var byte: Byte

    @DslValue(initial = "-9223372036854775808")
    var long: Long
}

@DslBuilder
interface ShadowedLongInitialDsl {
    @Suppress("PropertyName")
    @DslValue(initial = "-9223372036854775808")
    var Long: Long
}

interface SatisfiableMembers {
    operator fun component1(): String

    override fun toString(): String
}

@DslBuilder(supertype = SatisfiableMembers::class)
interface SatisfiableComponentDsl {
    val name: String
}

interface ConcreteMemberExtensions {
    val String.label: String
        get() = "label"

    fun String.describe(): String = "described"
}

@DslBuilder(supertype = ConcreteMemberExtensions::class)
interface ConcreteMemberExtensionDsl {
    val name: String
}

@DslBuilder
interface SpecConcreteMemberExtensionDsl {
    @DslValue(initial = "1")
    var value: Int

    val String.label: String
        get() = "label"
}

interface CovariantComponentWide {
    operator fun component1(): CharSequence
}

interface CovariantComponentNarrow {
    operator fun component1(): String
}

interface CovariantComponentSupertype : CovariantComponentWide, CovariantComponentNarrow

@DslBuilder(supertype = CovariantComponentSupertype::class)
interface CovariantComponentDsl {
    val name: String
}

interface ConcreteComponentWide {
    operator fun component1(): CharSequence = "default"
}

@DslBuilder(supertype = ConcreteComponentWide::class)
interface ConcreteComponentDsl {
    val name: String
}
