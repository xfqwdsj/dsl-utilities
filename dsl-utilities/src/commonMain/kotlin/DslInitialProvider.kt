package top.ltfan.dslutilities

/** Supplies a fresh initial value each time a generated builder is created. */
public fun interface DslInitialProvider<out T> {
    public fun provide(): T
}
