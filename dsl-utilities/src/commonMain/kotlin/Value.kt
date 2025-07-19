package top.ltfan.dslutilities

import kotlin.reflect.KProperty

interface Value<T> {
    var value: T
}

interface DslValue<T, P> : Value<T>, KProperty<P> {
    fun <R> bypassHooks(block: BypassedHooks<T>.() -> R): R

    @Dsl
    interface BypassedHooks<T> : Value<T>

    @DslMarker
    annotation class Dsl
}

class ValueProperty<I, O>(
    initial: I,
    private val beforeGet: DslValue<O, *>.(I) -> Unit = {},
    private val beforeSet: DslValue<O, *>.(O) -> Unit = {},
    private val getTransform: (I) -> O,
    private val setTransform: (O) -> I,
    private val afterGet: DslValue<O, *>.(O) -> Unit = {},
    private val afterSet: DslValue<O, *>.(I) -> Unit = {},
    getBypassedHooksValue: (DslValue.BypassedHooks<O>) -> Unit = {},
) {
    private var stored: I = initial

    private fun getValue() = getTransform(stored)

    private fun KProperty<*>.getValueWithHooks(): O {
        val dslValue = this.dslValue()
        dslValue.beforeGet(stored)
        return getValue().also { dslValue.afterGet(it) }
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>) = property.getValueWithHooks()

    private fun setValue(value: O) = setTransform(value).let {
        stored = it
        it
    }

    private fun KProperty<*>.setValueWithHooks(value: O) {
        val dslValue = dslValue()
        dslValue.beforeSet(value)
        setValue(value).also { dslValue.afterSet(it) }
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: O) {
        property.setValueWithHooks(value)
    }

    private fun KProperty<*>.value() = object : Value<O> {
        override var value: O
            get() = getValueWithHooks()
            set(value) {
                setValueWithHooks(value)
            }
    }

    private fun <P> KProperty<P>.dslValue(): DslValue<O, P> =
        object : DslValue<O, P>, Value<O> by value(), KProperty<P> by this {
            override fun <R> bypassHooks(block: DslValue.BypassedHooks<O>.() -> R) = bypassedHooksValue.block()
        }

    val bypassedHooksValue = object : DslValue.BypassedHooks<O> {
        override var value: O
            get() = getValue()
            set(value) {
                setValue(value)
            }
    }

    init {
        getBypassedHooksValue(bypassedHooksValue)
    }
}

class ListProperty<I, O>(
    initial: MutableList<I> = mutableListOf(),
    private val getTransform: DslMutableList<O>.(I) -> O,
    private val setTransform: DslMutableList<O>.(O) -> I,
    private val beforeGet: DslMutableList<O>.(Int) -> Unit = {},
    private val beforeSet: DslMutableList<O>.(Int, O) -> Unit = { _, _ -> },
    private val beforeRemove: DslMutableList<O>.(Int) -> Unit = {},
    private val beforeAccess: DslMutableList<O>.() -> Unit = {},
    private val beforeReplace: DslMutableList<O>.(MutableList<O>) -> Unit = {},
    private val accessTransform: DslMutableList<O>.() -> MutableList<O> = { this },
    getDslMutableList: (DslMutableList<O>) -> Unit = {},
) : DslReadWriteListProperty<O>, AbstractDslMutableList<I, O>(initial) {
    override fun getTransform(original: I): O = getTransform.invoke(this, original)
    override fun setTransform(original: O): I = setTransform.invoke(this, original)

    override fun getValue(thisRef: Any?, property: KProperty<*>): MutableList<O> {
        beforeAccess()
        return accessTransform()
    }

    private fun superReplace(list: MutableList<O>) = super.replace(list)

    override fun replace(list: MutableList<O>) {
        beforeReplace(list)
        superReplace(list)
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: MutableList<O>) {
        replace(value)
    }

    override fun get(index: Int): O {
        beforeGet(index)
        return originalGet(index)
    }

    override fun set(index: Int, element: O): O {
        beforeSet(index, element)
        return originalSet(index, element)
    }

    override fun add(index: Int, element: O) {
        beforeSet(index, element)
        originalAdd(index, element)
    }

    override fun removeAt(index: Int): O {
        beforeRemove(index)
        return originalRemoveAt(index)
    }

    override fun <R> bypassHooks(block: DslMutableList.BypassedHooks<O>.() -> R) = bypassedHooksList.block()

    val bypassedHooksList: DslMutableList.BypassedHooks<O> =
        object : DslMutableList.BypassedHooks<O>, DslReplaceableList<O>, AbstractMutableList<O>() {
            override fun get(index: Int) = originalGet(index)
            override fun set(index: Int, element: O) = originalSet(index, element)
            override fun add(index: Int, element: O) = originalAdd(index, element)
            override fun removeAt(index: Int) = originalRemoveAt(index)
            override fun replace(list: MutableList<O>) = superReplace(list)
            override val size get() = this@ListProperty.size
        }

    init {
        getDslMutableList(this)
    }
}
