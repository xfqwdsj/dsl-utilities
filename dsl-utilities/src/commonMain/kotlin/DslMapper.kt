package top.ltfan.dslutilities

/** Maps values between the declared type [O] and stored representation [I]. */
public interface DslMapper<I, O> {
    /** Maps the declared [value] to its stored representation. */
    public fun toStored(value: O): I

    /** Maps the [stored] representation back to the declared type. */
    public fun toValue(stored: I): O
}
