package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*

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
    val initialValue: CodeBlock,
    val mapper: Instantiation?,
    val validator: Instantiation?,
    val message: String,
) {
    var fieldName: String = "${name}Field"
}

internal class ListProperty(
    val name: String,
    val typeName: TypeName,
    val elementTypeName: TypeName,
    val elementType: KSType,
    val validator: Instantiation?,
    val message: String,
    val children: List<ChildSpec>,
) {
    var fieldName: String = "${name}Field"
}

/**
 * The Kotlin signature of a generated top-level function: its extension
 * receiver, the required value parameters, and the trailing child block.
 */
internal class GeneratedSignature(
    val receiver: KSType?,
    val parameters: List<KSType>,
    val blockReceiver: KSType,
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
