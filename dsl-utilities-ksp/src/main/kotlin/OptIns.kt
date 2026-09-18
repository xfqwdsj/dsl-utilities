package top.ltfan.dslutilities.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ksp.toClassName

/**
 * Carries lexical opt-ins from source declarations into their generated
 * file.
 */
internal fun fileOptIns(declarations: Iterable<KSDeclaration>): AnnotationSpec? {
    val markers = linkedSetOf<ClassName>()
    val sources = linkedSetOf<KSAnnotated>()
    for (declaration in declarations) {
        var current: KSDeclaration? = declaration
        while (current != null) {
            sources += current
            current.containingFile?.let(sources::add)
            current = current.parentDeclaration
        }
    }
    for (source in sources) {
        for (annotation in source.annotations) {
            if (!annotation.isMarker(OPT_IN)) continue
            for (marker in annotation.typeArray("markerClass")) {
                val markerDeclaration = marker.declaration as? KSClassDeclaration ?: continue
                markers += markerDeclaration.toClassName()
            }
        }
    }
    if (markers.isEmpty()) return null
    val arguments = CodeBlock.builder().apply {
        markers.forEachIndexed { index, marker ->
            if (index > 0) add(", ")
            add("%T::class", marker)
        }
    }.build()
    return AnnotationSpec.builder(OPT_IN)
        .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
        .addMember("%L", arguments)
        .build()
}
