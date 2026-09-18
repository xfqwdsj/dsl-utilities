@file:Suppress("unused")

package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.DslBuilder

class GenericOuter<T> {
    inner class GenericInner<U> {
        val owner: GenericOuter<T> get() = this@GenericOuter
    }
}

class LayeredOuter<T> {
    inner class LayeredMiddle<U> {
        val owner: LayeredOuter<T> get() = this@LayeredOuter

        inner class LayeredLeaf<V> {
            val owner: LayeredMiddle<U> get() = this@LayeredMiddle
        }
    }
}

class GappedOuter<T> {
    inner class GappedMiddle {
        val owner: GappedOuter<T> get() = this@GappedOuter

        inner class GappedLeaf<V> {
            val owner: GappedMiddle get() = this@GappedMiddle
        }
    }
}

@DslBuilder
interface GenericOuterInnerDsl {
    val inner: GenericOuter<String>.GenericInner<Int>

    val layered: LayeredOuter<String>.LayeredMiddle<Int>.LayeredLeaf<Long>

    val gapped: GappedOuter<String>.GappedMiddle.GappedLeaf<Long>
}
