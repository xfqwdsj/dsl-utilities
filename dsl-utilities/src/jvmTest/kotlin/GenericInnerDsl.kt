package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.DslBuilder

class GenericOuter<T> {
    inner class GenericInner<U>
}

@DslBuilder
interface GenericOuterInnerDsl {
    val inner: GenericOuter<String>.GenericInner<Int>
}
