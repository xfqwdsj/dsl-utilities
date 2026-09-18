package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.DslBuilder
import top.ltfan.dslutilities.DslList
import top.ltfan.dslutilities.test.collision.toList

@DslBuilder
interface ToListTypeCaptureDsl {
    val other: toList

    @DslList
    var items: MutableList<Int>
}
