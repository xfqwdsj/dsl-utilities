@file:OptIn(ExperimentalDslType::class)

package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.DslBuilder

@DslBuilder
interface FileOptInDsl {
    val value: ExperimentalValue
}
