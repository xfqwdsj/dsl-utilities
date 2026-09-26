package top.ltfan.dslutilities

/** Shared contract for named list blocks in generated DSLs. */
public interface DslListScope<T> {
    public val elements: MutableList<T>
}
