package top.ltfan.dslutilities

import kotlin.jvm.JvmName
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Base class providing DSL property delegates for value handling,
 * including transformation, validation, and list management.
 */
abstract class ValueDsl {
    /**
     * Creates a property delegate with custom get/set transformations.
     *
     * @param defaultValue The initial value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     */
    protected open fun <I, O> value(
        defaultValue: I,
        getTransform: KProperty<*>.(I) -> O,
        setTransform: KProperty<*>.(O) -> I
    ) = object : ReadWriteProperty<Any?, O> {
        private var stored: I = defaultValue

        override fun getValue(thisRef: Any?, property: KProperty<*>): O =
            property.getTransform(stored)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: O) {
            stored = property.setTransform(value)
        }
    }

    /**
     * Creates a property delegate with validation and transformation logic.
     *
     * @param defaultValue The initial value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     * @param validateGet Validation for get operation.
     * @param validateSet Validation for set operation.
     * @param messageBuilder Error message builder.
     */
    protected open fun <I, O> conditional(
        defaultValue: I,
        beforeGet: KProperty<*>.(I) -> Boolean = { true },
        beforeSet: KProperty<*>.(O) -> Boolean = { true },
        getTransform: KProperty<*>.(I) -> O,
        setTransform: KProperty<*>.(O) -> I,
        validateGet: KProperty<*>.(O) -> Boolean = { true },
        validateSet: KProperty<*>.(I) -> Boolean = { true },
        messageBuilder: (KProperty<*>.() -> Any) = { "Invalid value for property $name." },
    ) = value(
        defaultValue = defaultValue,
        getTransform = {
            require(beforeGet(it)) { messageBuilder() }
            val value = getTransform(it)
            require(validateGet(value)) { messageBuilder() }
            value
        },
        setTransform = {
            require(beforeSet(it)) { messageBuilder() }
            val value = setTransform(it)
            require(validateSet(value)) { messageBuilder() }
            value
        }
    )

    /**
     * Creates a required (non-null) property delegate.
     *
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     * @param messageBuilder Error message builder.
     */
    protected open fun <T> required(
        getTransform: KProperty<*>.(T) -> T = { it },
        setTransform: KProperty<*>.(T) -> T = { it },
        messageBuilder: (KProperty<*>.() -> Any) = { "Property is required and cannot be null." },
    ) = conditional(
        defaultValue = null as T?,
        beforeGet = { it != null },
        beforeSet = { it != null },
        getTransform = { getTransform(it!!) },
        setTransform = { setTransform(it!!) },
        messageBuilder = messageBuilder
    )

    /**
     * Creates a property delegate with a default value and optional
     * transformations.
     *
     * @param defaultValue The initial value.
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     */
    protected open fun <T> prepared(
        defaultValue: T,
        getTransform: KProperty<*>.(T) -> T = { it },
        setTransform: KProperty<*>.(T) -> T = { it },
    ) = value(defaultValue, getTransform, setTransform)

    /**
     * Creates an optional property delegate (nullable).
     *
     * @param getTransform Transformation applied on get.
     * @param setTransform Transformation applied on set.
     */
    protected open fun <T> optional(
        getTransform: KProperty<*>.(T?) -> T? = { it },
        setTransform: KProperty<*>.(T?) -> T? = { it },
    ) = prepared(null, getTransform, setTransform)

    /**
     * Internal: Creates a read-write list property delegate with
     * transformations and hooks.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeAccess Hook before accessing the list.
     * @param beforeReplace Hook before replacing the list.
     */
    private fun <I, O> dslReadWriteListProperty(
        initial: MutableList<I> = mutableListOf(),
        getTransform: MutableList<O>.(I) -> O,
        setTransform: MutableList<O>.(O) -> I,
        beforeAccess: (MutableList<O>) -> Unit = {},
        beforeReplace: (MutableList<O>) -> Unit = {},
    ): DslReadWriteListProperty<O> =
        object : DslReadWriteListProperty<O>, MutableList<O>, AbstractDslMutableList<I, O>(initial) {
            override fun getTransform(original: I): O = getTransform.invoke(this, original)
            override fun setTransform(original: O): I = setTransform.invoke(this, original)

            override fun getValue(thisRef: Any?, property: KProperty<*>): MutableList<O> {
                beforeAccess(this)
                return this
            }

            override fun replace(list: MutableList<O>) {
                beforeReplace(list)
                super.replace(list)
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: MutableList<O>) {
                replace(value)
            }
        }

    /**
     * Creates a mutable list property delegate with transformations and a
     * hook.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeAccess Hook before accessing the list.
     */
    protected open fun <I, O> value(
        initial: MutableList<I> = mutableListOf(),
        getTransform: MutableList<O>.(I) -> O,
        setTransform: MutableList<O>.(O) -> I,
        beforeAccess: (MutableList<O>) -> Unit = {},
    ) = object : DslReadOnlyListProperty<O> {
        val delegate = dslReadWriteListProperty(initial, getTransform, setTransform, beforeAccess)
        override fun getValue(thisRef: Any?, property: KProperty<*>) = delegate.getValue(thisRef, property)
    }

    /**
     * Creates a replaceable mutable list property delegate with
     * transformations and hooks.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeAccess Hook before accessing the list.
     * @param beforeReplace Hook before replacing the list.
     */
    protected open fun <I, O> value(
        initial: MutableList<I> = mutableListOf(),
        getTransform: MutableList<O>.(I) -> O,
        setTransform: MutableList<O>.(O) -> I,
        beforeAccess: (MutableList<O>) -> Unit = {},
        beforeReplace: (MutableList<O>) -> Unit = {},
    ) = object : DslReadWriteListProperty<O> {
        val delegate = dslReadWriteListProperty(initial, getTransform, setTransform, beforeAccess, beforeReplace)
        override fun getValue(thisRef: Any?, property: KProperty<*>) = delegate.getValue(thisRef, property)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: MutableList<O>) {
            delegate.setValue(thisRef, property, value)
        }
    }

    /**
     * Creates a non-replaceable mutable list property delegate with element
     * transformations and a hook.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeAccess Hook before accessing the list.
     */
    protected open fun <T> list(
        initial: MutableList<T> = mutableListOf(),
        getTransform: (T) -> T = { it },
        setTransform: (T) -> T = { it },
        beforeAccess: (MutableList<T>) -> Unit = {},
    ) = value(
        initial = initial,
        getTransform = { getTransform(it) },
        setTransform = { setTransform(it) },
        beforeAccess = beforeAccess,
    )

    /**
     * Creates a replaceable list property delegate with element
     * transformations and hooks.
     *
     * @param initial Initial list.
     * @param getTransform Transformation for getting elements.
     * @param setTransform Transformation for setting elements.
     * @param beforeAccess Hook before accessing the list.
     * @param beforeReplace Hook before replacing the list.
     */
    protected open fun <T> replaceableList(
        initial: MutableList<T> = mutableListOf(),
        getTransform: (T) -> T = { it },
        setTransform: (T) -> T = { it },
        beforeAccess: (MutableList<T>) -> Unit = {},
        beforeReplace: (MutableList<T>) -> Unit = {},
    ) = value(
        initial = initial,
        getTransform = { getTransform(it) },
        setTransform = { setTransform(it) },
        beforeAccess = beforeAccess,
        beforeReplace = beforeReplace,
    )
}

/**
 * Extension of [ValueDsl] that adds locking functionality to prevent
 * further modifications.
 */
abstract class LockableValueDsl : ValueDsl() {
    private var _isLocked = false
    private var _isInBeforeAccess = false

    /** Indicates whether the DSL is locked. */
    val isLocked: Boolean
        get() = _isLocked

    /** Locks the DSL, preventing further modifications. */
    protected fun lock() {
        _isLocked = true
    }

    @JvmName("validateSetTransform")
    private inline fun <I, O> validate(crossinline setTransform: MutableList<O>.(O) -> I): MutableList<O>.(O) -> I = {
        check(!isLocked || _isInBeforeAccess) {
            "MutableList ${this::class.simpleName} of class ${this@LockableValueDsl::class.simpleName} is locked and its elements cannot be modified."
        }
        setTransform(it)
    }

    @JvmName("validateBeforeReplace")
    private inline fun <T> validate(crossinline beforeReplace: (MutableList<T>) -> Unit): (MutableList<T>) -> Unit = {
        check(!isLocked || _isInBeforeAccess) {
            "MutableList ${it::class.simpleName} of class ${this::class.simpleName} is locked and its elements cannot be modified."
        }
        beforeReplace(it)
    }

    private inline fun <I, O> validate(crossinline setTransform: KProperty<*>.(O) -> I): KProperty<*>.(O) -> I = {
        check(!isLocked || _isInBeforeAccess) { "Class ${this@LockableValueDsl::class.simpleName} is locked and the property $name cannot be modified." }
        setTransform(it)
    }

    private inline fun <T> wrapBeforeAccess(crossinline beforeAccess: (MutableList<T>) -> Unit): (MutableList<T>) -> Unit =
        { list ->
            _isInBeforeAccess = true
            try {
                beforeAccess(list)
            } finally {
                _isInBeforeAccess = false
            }
        }

    override fun <I, O> value(
        defaultValue: I,
        getTransform: KProperty<*>.(I) -> O,
        setTransform: KProperty<*>.(O) -> I,
    ) = super.value(defaultValue, getTransform, validate(setTransform))

    override fun <I, O> value(
        initial: MutableList<I>,
        getTransform: MutableList<O>.(I) -> O,
        setTransform: MutableList<O>.(O) -> I,
        beforeAccess: (MutableList<O>) -> Unit,
    ) = super.value(
        initial = initial,
        getTransform = getTransform,
        setTransform = validate(setTransform),
        beforeAccess = wrapBeforeAccess(beforeAccess)
    )

    override fun <I, O> value(
        initial: MutableList<I>,
        getTransform: MutableList<O>.(I) -> O,
        setTransform: MutableList<O>.(O) -> I,
        beforeAccess: (MutableList<O>) -> Unit,
        beforeReplace: (MutableList<O>) -> Unit,
    ) = super.value(
        initial = initial,
        getTransform = getTransform,
        setTransform = validate(setTransform),
        beforeAccess = wrapBeforeAccess(beforeAccess),
        beforeReplace = validate(beforeReplace),
    )
}
