package top.ltfan.dslutilities

import kotlin.reflect.KProperty

/**
 * An abstract class that provides a mutable list DSL implementation.
 *
 * This class allows you to create a mutable list that transforms elements
 * from type [I] to type [O] and vice versa. It provides methods to get,
 * set, add, and remove elements while maintaining the transformation
 * logic.
 *
 * @param I The original type of the elements in the list.
 * @param O The output type of the elements in the list after
 *    transformation.
 */
abstract class AbstractDslMutableList<I, O>(
    initial: MutableList<I> = mutableListOf(),
) : DslReplaceableList<O>, AbstractMutableList<O>() {
    /**
     * The delegate list that stores the original type [I] values. This is
     * mutable and can be replaced with a new list.
     */
    protected var delegate: MutableList<I> = initial

    /**
     * Transforms the original type [I] to the output type [O]. This method
     * should be implemented to define how the transformation from [I] to [O]
     * is done.
     */
    protected abstract fun getTransform(original: I): O

    /**
     * Transforms the output type [O] back to the original type [I]. This
     * method should be implemented to define how the transformation from [O]
     * to [I] is done.
     */
    protected abstract fun setTransform(original: O): I

    override fun replace(list: MutableList<O>) {
        delegate = list.map { setTransform(it) }.toMutableList()
    }

    override fun get(index: Int) = getTransform(delegate[index])

    override fun set(index: Int, element: O) = get(index).also {
        delegate[index] = setTransform(element)
    }

    override fun add(index: Int, element: O) {
        delegate.add(index, setTransform(element))
    }

    override fun removeAt(index: Int) = get(index).also {
        delegate.removeAt(index)
    }

    override val size get() = delegate.size
}

interface DslReadOnlyListProperty<T> {
    /**
     * Gets the value of the property as a mutable list of type [T]. The
     * operations on this list is under the control of the DSL. DO NOT use this
     * list when extracting the final list, as it will not be transformed.
     *
     * @return A mutable list of type [T].
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): MutableList<T>
}

interface DslReadWriteListProperty<T> : DslReadOnlyListProperty<T> {
    /** Replaces the current list with a new one. */
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: MutableList<T>)
}

interface DslReplaceableList<T> {
    /** Replaces the current list with a new one. */
    fun replace(list: MutableList<T>)
}
