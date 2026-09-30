package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

internal data class MapperInfo(
    val target: Instantiation,
    val storageTypeName: TypeName,
    val storedType: KSType,
)

internal class RequiredProperty(
    val name: String,
    val typeName: TypeName,
    val type: KSType,
    val validator: Instantiation?,
    val message: String,
)

internal class Parameter(
    val name: String,
    val typeName: TypeName,
)

internal class ValueProperty(
    val name: String,
    val typeName: TypeName,
    val type: KSType,
    val storageTypeName: TypeName,
    val initialValue: CodeBlock?,
    val initialProvider: Instantiation?,
    val mapper: Instantiation?,
    val hook: Instantiation?,
    val validator: Instantiation?,
    val message: String,
    val required: Boolean,
    val setterName: String? = null,
    val setterParameterName: String? = null,
) {
    var fieldName: String = "${name}Field"
    lateinit var isSetFieldName: String
}

internal class ListProperty(
    val name: String,
    val typeName: TypeName,
    val type: KSType,
    val elementTypeName: TypeName,
    val elementType: KSType,
    val validator: Instantiation?,
    val hook: Instantiation?,
    val message: String,
    val children: List<ChildSpec>,
    val scopeName: String,
    val appendName: String? = null,
    val appendParameterName: String? = null,
) {
    var fieldName: String = "${name}Field"
    var scopeTypeName: ClassName? = null
}

/**
 * The Kotlin signature of a generated top-level function: its extension
 * receiver, the required value parameters, and the trailing child block.
 */
internal class GeneratedSignature(
    val receiver: KSType?,
    val parameters: List<KSType>,
    val blockReceiver: KSType? = null,
    val generatedBlockReceiver: ClassName? = null,
)

/**
 * A DslBuilder interface used as a child: its generated builder and
 * result classes are referenced as [ClassName] values so that the parent
 * generated code can construct and build it.
 */
internal class ChildSpec(
    val functionName: String,
    val specTypeName: ClassName,
    val builderType: ClassName,
    val resultType: ClassName,
    val resultSupertype: KSType?,
    val specType: KSType,
    val builderVisibility: KModifier,
    val resultVisibility: KModifier,
    val required: List<RequiredProperty>,
    val requiresConfiguration: Boolean,
)

internal class ResultSupertype(
    val type: KSType,
    val declaration: KSClassDeclaration,
    val typeName: TypeName,
    val visibility: KModifier,
)

internal class ChildScope(
    val name: String,
    val parameters: List<Parameter>,
    val blockName: String,
    val blockTypeName: TypeName,
    val child: ChildSpec,
) {
    var fieldName: String = "${name}Field"
}
