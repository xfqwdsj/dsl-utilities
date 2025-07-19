package top.ltfan.dslutilities

import kotlin.jvm.JvmName
import kotlin.reflect.KProperty

/**
 * Base class providing DSL property delegates for value handling,
 * including transformation, validation, and list management.
 */
@ValueDsl.Dsl
abstract class ValueDsl {
    /**
     * Creates a property delegate with custom get/set transformations.
     *
     * @param initial The initial value.
     * @param beforeGet Pre-hook before getting the value.
     * @param beforeSet Pre-hook before setting the value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     * @param afterGet Post-hook after getting the value.
     * @param afterSet Post-hook after setting the value.
     * @param getBypassedHooksValue Function to get the
     *   [DslValue.BypassedHooks] instance.
     */
    protected open fun <I, O> value(
        initial: I,
        beforeGet: DslValue<O, *>.(I) -> Unit = {},
        beforeSet: DslValue<O, *>.(O) -> Unit = {},
        getTransform: (I) -> O,
        setTransform: (O) -> I,
        afterGet: DslValue<O, *>.(O) -> Unit = {},
        afterSet: DslValue<O, *>.(I) -> Unit = {},
        getBypassedHooksValue: (DslValue.BypassedHooks<O>) -> Unit = {},
    ) = ValueProperty(
        initial = initial,
        beforeGet = beforeGet,
        beforeSet = beforeSet,
        getTransform = getTransform,
        setTransform = setTransform,
        afterGet = afterGet,
        afterSet = afterSet,
        getBypassedHooksValue = getBypassedHooksValue,
    )

    /**
     * Creates a property delegate with validation and transformation logic.
     *
     * @param initial The initial value.
     * @param beforeGet Pre-check before getting the value.
     * @param beforeSet Pre-check before setting the value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     * @param validateGet Validation for get operation after transformation.
     * @param validateSet Validation for set operation after transformation.
     * @param getBypassedHooksValue Function to get the
     *   [DslValue.BypassedHooks] instance.
     * @param messageBuilder Error message builder.
     */
    protected open fun <I, O> conditional(
        initial: I,
        beforeGet: DslValue<O, *>.(I) -> Boolean = { true },
        beforeSet: DslValue<O, *>.(O) -> Boolean = { true },
        getTransform: (I) -> O,
        setTransform: (O) -> I,
        validateGet: KProperty<*>.(O) -> Boolean = { true },
        validateSet: KProperty<*>.(I) -> Boolean = { true },
        getBypassedHooksValue: (DslValue.BypassedHooks<O>) -> Unit = {},
        messageBuilder: (KProperty<*>.() -> Any) = { "Invalid value for property $name." },
    ) = value(
        initial = initial,
        beforeGet = { require(beforeGet(it)) { this.messageBuilder() } },
        beforeSet = { require(beforeSet(it)) { this.messageBuilder() } },
        getTransform = getTransform,
        setTransform = setTransform,
        afterGet = { require(validateGet(it)) { this.messageBuilder() } },
        afterSet = { require(validateSet(it)) { this.messageBuilder() } },
        getBypassedHooksValue = getBypassedHooksValue,
    )

    /**
     * Creates a required (non-null) property delegate.
     *
     * @param beforeGet Pre-hook before getting the value.
     * @param beforeSet Pre-hook before setting the value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     * @param validateGet Validation for get operation after transformation.
     * @param validateSet Validation for set operation after transformation.
     * @param getBypassedHooksValue Function to get the
     *   [DslValue.BypassedHooks] instance.
     * @param messageBuilder Error message builder.
     */
    protected open fun <T> required(
        beforeGet: DslValue<T, *>.(T?) -> Unit = {},
        beforeSet: DslValue<T, *>.(T?) -> Unit = {},
        getTransform: (T) -> T = { it },
        setTransform: (T) -> T = { it },
        validateGet: KProperty<*>.(T) -> Boolean = { true },
        validateSet: KProperty<*>.(T) -> Boolean = { true },
        getBypassedHooksValue: (DslValue.BypassedHooks<T>) -> Unit = {},
        messageBuilder: (KProperty<*>.() -> Any) = { "Property $name is required and cannot be null." },
    ) = conditional(
        initial = null as T?,
        beforeGet = {
            beforeGet(it)
            it != null
        },
        beforeSet = {
            beforeSet(it)
            it != null
        },
        getTransform = { getTransform(it!!) },
        setTransform = { setTransform(it!!) },
        validateGet = validateGet,
        validateSet = { validateSet(it!!) },
        getBypassedHooksValue = getBypassedHooksValue,
        messageBuilder = messageBuilder,
    )

    /**
     * Creates a property delegate with a default value and optional
     * transformations.
     *
     * @param initial The initial value.
     * @param beforeGet Pre-hook before getting the value.
     * @param beforeSet Pre-hook before setting the value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     * @param afterGet Post-hook after getting the value.
     * @param afterSet Post-hook after setting the value.
     * @param getBypassedHooksValue Function to get the
     *   [DslValue.BypassedHooks] instance.
     */
    protected open fun <T> prepared(
        initial: T,
        beforeGet: DslValue<T, *>.(T) -> Unit = {},
        beforeSet: DslValue<T, *>.(T) -> Unit = {},
        getTransform: (T) -> T = { it },
        setTransform: (T) -> T = { it },
        afterGet: DslValue<T, *>.(T) -> Unit = {},
        afterSet: DslValue<T, *>.(T) -> Unit = {},
        getBypassedHooksValue: (DslValue.BypassedHooks<T>) -> Unit = {},
    ) = value(
        initial = initial,
        beforeGet = beforeGet,
        beforeSet = beforeSet,
        getTransform = getTransform,
        setTransform = setTransform,
        afterGet = afterGet,
        afterSet = afterSet,
        getBypassedHooksValue = getBypassedHooksValue,
    )

    /**
     * Creates an optional property delegate (nullable).
     *
     * @param beforeGet Pre-hook before getting the value.
     * @param beforeSet Pre-hook before setting the value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     * @param afterGet Post-hook after getting the value.
     * @param afterSet Post-hook after setting the value.
     * @param getBypassedHooksValue Function to get the
     *   [DslValue.BypassedHooks] instance.
     */
    protected open fun <T> optional(
        beforeGet: DslValue<T?, *>.(T?) -> Unit = {},
        beforeSet: DslValue<T?, *>.(T?) -> Unit = {},
        getTransform: (T?) -> T? = { it },
        setTransform: (T?) -> T? = { it },
        afterGet: DslValue<T?, *>.(T?) -> Unit = {},
        afterSet: DslValue<T?, *>.(T?) -> Unit = {},
        getBypassedHooksValue: (DslValue.BypassedHooks<T?>) -> Unit = {},
    ) = prepared(
        initial = null,
        beforeGet = beforeGet,
        beforeSet = beforeSet,
        getTransform = getTransform,
        setTransform = setTransform,
        afterGet = afterGet,
        afterSet = afterSet,
        getBypassedHooksValue = getBypassedHooksValue,
    )

    /**
     * Creates a read-write list property delegate with transformations and
     * hooks.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeGet Hook before getting an element.
     * @param beforeSet Hook before setting an element.
     * @param beforeRemove Hook before removing an element.
     * @param beforeAccess Hook before accessing the list.
     * @param beforeReplace Hook before replacing the list.
     * @param accessTransform Transformation to the list when accessed.
     * @param getDslMutableList Function to get the [DslMutableList] instance.
     */
    protected open fun <I, O> value(
        initial: MutableList<I> = mutableListOf(),
        getTransform: DslMutableList<O>.(I) -> O,
        setTransform: DslMutableList<O>.(O) -> I,
        beforeGet: DslMutableList<O>.(Int) -> Unit = {},
        beforeSet: DslMutableList<O>.(Int, O) -> Unit = { _, _ -> },
        beforeRemove: DslMutableList<O>.(Int) -> Unit = {},
        beforeAccess: DslMutableList<O>.() -> Unit = {},
        beforeReplace: DslMutableList<O>.(MutableList<O>) -> Unit = {},
        accessTransform: DslMutableList<O>.() -> MutableList<O> = { this },
        getDslMutableList: (DslMutableList<O>) -> Unit = {},
    ) = ListProperty(
        initial = initial,
        getTransform = getTransform,
        setTransform = setTransform,
        beforeGet = beforeGet,
        beforeSet = beforeSet,
        beforeRemove = beforeRemove,
        beforeAccess = beforeAccess,
        beforeReplace = beforeReplace,
        accessTransform = accessTransform,
        getDslMutableList = getDslMutableList,
    )

    /**
     * Creates a mutable list property delegate with transformations and a
     * hook.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeGet Hook before getting an element.
     * @param beforeSet Hook before setting an element.
     * @param beforeRemove Hook before removing an element.
     * @param beforeAccess Hook before accessing the list.
     * @param accessTransform Transformation to the list when accessed.
     * @param getDslMutableList Function to get the [DslMutableList] instance.
     */
    protected open fun <I, O> value(
        initial: MutableList<I> = mutableListOf(),
        getTransform: DslMutableList<O>.(I) -> O,
        setTransform: DslMutableList<O>.(O) -> I,
        beforeGet: DslMutableList<O>.(index: Int) -> Unit = {},
        beforeSet: DslMutableList<O>.(index: Int, element: O) -> Unit = { _, _ -> },
        beforeRemove: DslMutableList<O>.(index: Int) -> Unit = {},
        beforeAccess: DslMutableList<O>.() -> Unit = {},
        accessTransform: DslMutableList<O>.() -> MutableList<O> = { this },
        getDslMutableList: (DslMutableList<O>) -> Unit = {},
    ) = object : DslReadOnlyListProperty<O> by value(
        initial = initial,
        getTransform = getTransform,
        setTransform = setTransform,
        beforeGet = beforeGet,
        beforeSet = beforeSet,
        beforeRemove = beforeRemove,
        beforeAccess = beforeAccess,
        beforeReplace = {},
        accessTransform = accessTransform,
        getDslMutableList = getDslMutableList,
    ) {}

    /**
     * Creates a non-replaceable mutable list property delegate with element
     * transformations and a hook.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeGet Hook before getting an element.
     * @param beforeSet Hook before setting an element.
     * @param beforeRemove Hook before removing an element.
     * @param beforeAccess Hook before accessing the list.
     * @param accessTransform Transformation to the list when accessed.
     * @param getDslMutableList Function to get the [DslMutableList] instance.
     */
    protected open fun <T> list(
        initial: MutableList<T> = mutableListOf(),
        getTransform: DslMutableList<T>.(T) -> T = { it },
        setTransform: DslMutableList<T>.(T) -> T = { it },
        beforeGet: DslMutableList<T>.(index: Int) -> Unit = {},
        beforeSet: DslMutableList<T>.(index: Int, element: T) -> Unit = { _, _ -> },
        beforeRemove: DslMutableList<T>.(index: Int) -> Unit = {},
        beforeAccess: DslMutableList<T>.() -> Unit = {},
        accessTransform: DslMutableList<T>.() -> MutableList<T> = { this },
        getDslMutableList: (DslMutableList<T>) -> Unit = {},
    ) = value(
        initial = initial,
        getTransform = { getTransform(it) },
        setTransform = { setTransform(it) },
        beforeGet = beforeGet,
        beforeSet = beforeSet,
        beforeRemove = beforeRemove,
        beforeAccess = beforeAccess,
        accessTransform = accessTransform,
        getDslMutableList = getDslMutableList,
    )

    /**
     * Creates a replaceable list property delegate with element
     * transformations and hooks.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeGet Hook before getting an element.
     * @param beforeSet Hook before setting an element.
     * @param beforeRemove Hook before removing an element.
     * @param beforeAccess Hook before accessing the list.
     * @param beforeReplace Hook before replacing the list.
     * @param accessTransform Transformation to the list when accessed.
     * @param getDslMutableList Function to get the [DslMutableList] instance.
     */
    protected open fun <T> replaceableList(
        initial: MutableList<T> = mutableListOf(),
        getTransform: (T) -> T = { it },
        setTransform: (T) -> T = { it },
        beforeGet: DslMutableList<T>.(index: Int) -> Unit = {},
        beforeSet: DslMutableList<T>.(index: Int, element: T) -> Unit = { _, _ -> },
        beforeRemove: DslMutableList<T>.(index: Int) -> Unit = {},
        beforeAccess: DslMutableList<T>.() -> Unit = {},
        beforeReplace: DslMutableList<T>.(MutableList<T>) -> Unit = {},
        accessTransform: DslMutableList<T>.() -> MutableList<T> = { this },
        getDslMutableList: (DslMutableList<T>) -> Unit = {},
    ) = value(
        initial = initial,
        getTransform = { getTransform(it) },
        setTransform = { setTransform(it) },
        beforeGet = beforeGet,
        beforeSet = beforeSet,
        beforeRemove = beforeRemove,
        beforeAccess = beforeAccess,
        beforeReplace = beforeReplace,
        accessTransform = accessTransform,
        getDslMutableList = getDslMutableList,
    )

    /**
     * Attempts to bypass hooks on a [MutableList] if it is an instance of
     * [DslMutableList].
     *
     * This function allows executing a block of code on the list without
     * triggering any hooks defined in the [DslMutableList]. If the list
     * is not a [DslMutableList], the [failed] callback is executed.
     *
     * Note: Using this function could not avoid triggering the hooks of
     * accessing the list, so it should be used with caution. It is recommended
     * to use this only when you are sure that the list is a [DslMutableList].
     *
     * @param T The type of elements in the list.
     * @param R The return type of the block to be executed.
     * @param failed A callback function to be executed if the list is not a
     *   [DslMutableList]. Defaults to an empty function.
     * @param block The block of code to execute on the list, bypassing hooks.
     * @return The result of the block execution if successful, or null if the
     *   list is not a [DslMutableList].
     */
    inline fun <T, R> MutableList<T>.tryBypassHooks(
        failed: () -> Unit = {}, noinline block: MutableList<T>.() -> R
    ): R? {
        val result = (this as? DslMutableList)?.bypassHooks(block)
        if (result == null) failed()
        return result
    }

    @DslMarker
    annotation class Dsl
}

/**
 * Extension of [ValueDsl] that adds locking functionality to prevent
 * further modifications.
 */
abstract class LockableValueDsl : ValueDsl() {
    private var _isLocked = false

    /** Indicates whether the DSL is locked. */
    val isLocked: Boolean
        get() = _isLocked

    /** Locks the DSL, preventing further modifications. */
    protected fun lock() {
        _isLocked = true
    }

    @JvmName("injectCheck1")
    private inline fun <I, O> injectCheck(crossinline hook: DslValue<O, *>.(O) -> I): DslValue<O, *>.(O) -> I = {
        check(!isLocked) { "Class ${this@LockableValueDsl::class.simpleName} is locked and the property $name cannot be modified." }
        hook(it)
    }

    @JvmName("injectCheck2")
    private inline fun <T, P, R> injectCheck(
        crossinline hook: DslMutableList<T>.(P) -> R
    ): DslMutableList<T>.(P) -> R = {
        check(!isLocked) {
            "MutableList ${this::class.simpleName} of class ${this@LockableValueDsl::class.simpleName} is locked and its elements cannot be modified."
        }
        hook(it)
    }

    @JvmName("injectCheck3")
    private inline fun <T, P1, P2, R> injectCheck(
        crossinline hook: DslMutableList<T>.(P1, P2) -> R
    ): DslMutableList<T>.(P1, P2) -> R = { p1, p2 ->
        check(!isLocked) {
            "MutableList ${this::class.simpleName} of class ${this@LockableValueDsl::class.simpleName} is locked and its elements cannot be modified."
        }
        hook(p1, p2)
    }

    override fun <I, O> value(
        initial: I,
        beforeGet: DslValue<O, *>.(I) -> Unit,
        beforeSet: DslValue<O, *>.(O) -> Unit,
        getTransform: (I) -> O,
        setTransform: (O) -> I,
        afterGet: DslValue<O, *>.(O) -> Unit,
        afterSet: DslValue<O, *>.(I) -> Unit,
        getBypassedHooksValue: (DslValue.BypassedHooks<O>) -> Unit
    ): ValueProperty<I, O> = super.value(
        initial = initial,
        beforeGet = beforeGet,
        beforeSet = injectCheck(beforeSet),
        getTransform = getTransform,
        setTransform = setTransform,
        afterGet = afterGet,
        afterSet = afterSet,
        getBypassedHooksValue = getBypassedHooksValue,
    )

    override fun <I, O> value(
        initial: MutableList<I>,
        getTransform: DslMutableList<O>.(I) -> O,
        setTransform: DslMutableList<O>.(O) -> I,
        beforeGet: DslMutableList<O>.(Int) -> Unit,
        beforeSet: DslMutableList<O>.(Int, O) -> Unit,
        beforeRemove: DslMutableList<O>.(Int) -> Unit,
        beforeAccess: DslMutableList<O>.() -> Unit,
        beforeReplace: DslMutableList<O>.(MutableList<O>) -> Unit,
        accessTransform: DslMutableList<O>.() -> MutableList<O>,
        getDslMutableList: (DslMutableList<O>) -> Unit
    ): ListProperty<I, O> = super.value(
        initial = initial,
        getTransform = getTransform,
        setTransform = setTransform,
        beforeGet = beforeGet,
        beforeSet = injectCheck(beforeSet),
        beforeRemove = injectCheck(beforeRemove),
        beforeAccess = beforeAccess,
        beforeReplace = injectCheck(beforeReplace),
        accessTransform = accessTransform,
        getDslMutableList = getDslMutableList
    )
}
