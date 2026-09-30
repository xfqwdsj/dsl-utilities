package top.ltfan.dslutilities.test

import top.ltfan.dslutilities.DslBuilder
import top.ltfan.dslutilities.DslList
import top.ltfan.dslutilities.DslValue

sealed interface RecordedScope {
    val orientation: String
}

@DslBuilder(generateFunction = false, resultName = "RecordedDefinition")
internal interface RecordedScopeDsl : RecordedScope {
    @DslList(resultName = "items")
    fun addItem(item: String)

    @DslValue(resultName = "header")
    fun setHeader(header: String?)
}

interface AppendBase<T> {
    @DslList(resultName = "entries")
    fun append(entry: T)
}

@DslBuilder(generateFunction = false)
internal interface InheritedAppendDsl : AppendBase<String>

@DslBuilder(generateFunction = false)
internal interface CollidingWriteParametersDsl {
    @DslList(resultName = "items")
    fun append(itemsField: MutableList<String>)

    @DslValue(resultName = "value")
    fun setValue(valueField: String?)
}
