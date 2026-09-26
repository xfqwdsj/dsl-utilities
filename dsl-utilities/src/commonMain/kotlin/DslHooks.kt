package top.ltfan.dslutilities

/**
 * Runs before a generated builder validates its values and takes its
 * snapshot.
 */
public interface DslBuildHook<in S> {
    public fun beforeBuild(scope: S)
}

/** Observes reads and writes of a generated value property. */
public interface DslValueHook<in T> {
    public fun beforeSet(value: T) {}

    public fun beforeAccess() {}
}

/** Observes access and element writes to a generated list property. */
public interface DslListHook<in T> {
    public fun beforeSet(element: T) {}

    public fun beforeAccess() {}
}

/** Mutable list used by generated builders to intercept direct mutations. */
public class HookedDslList<T>(private val hook: DslListHook<T>) : AbstractMutableList<T>() {
    private val elements = mutableListOf<T>()

    override val size: Int
        get() {
            hook.beforeAccess()
            return elements.size
        }

    override fun get(index: Int): T {
        hook.beforeAccess()
        return elements[index]
    }

    override fun add(index: Int, element: T) {
        hook.beforeSet(element)
        elements.add(index, element)
    }

    override fun set(index: Int, element: T): T {
        hook.beforeSet(element)
        return elements.set(index, element)
    }

    override fun removeAt(index: Int): T = elements.removeAt(index)

    /** Replaces all elements after the hook accepts every incoming value. */
    public fun replaceWith(values: Collection<T>) {
        val snapshot = values.toList()
        snapshot.forEach(hook::beforeSet)
        elements.clear()
        elements.addAll(snapshot)
    }
}
