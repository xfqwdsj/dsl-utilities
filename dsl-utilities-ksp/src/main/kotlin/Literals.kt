package top.ltfan.dslutilities.ksp

import com.squareup.kotlinpoet.*

/** Unsigned integral types, which KotlinPoet defines no constants for. */
private val UINT = ClassName("kotlin", "UInt")

private val ULONG = ClassName("kotlin", "ULong")

private val USHORT = ClassName("kotlin", "UShort")

private val UBYTE = ClassName("kotlin", "UByte")

/**
 * One supported `@DslValue.initial` type: the class it parses, the detail
 * of a constant that does not fit, and the literal it renders to.
 */
internal class InitialFormat(
    val type: ClassName,
    val detail: String,
    val parse: (String) -> CodeBlock?,
)

/**
 * The supported `@DslValue.initial` types, in the order the diagnostic
 * lists them. The dispatch and the message derive from this table, so
 * adding a type keeps both in sync.
 */
internal val initialFormats = listOf(
    InitialFormat(BYTE, "not a Byte constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }
            ?.let { literalNumber(if (it < 0) "($it).toByte()" else "$it.toByte()") }
    },
    InitialFormat(SHORT, "not a Short constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
            ?.let { literalNumber(if (it < 0) "($it).toShort()" else "$it.toShort()") }
    },
    InitialFormat(INT, "not an Int constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.let { literalNumber(it.toString()) }
    },
    InitialFormat(LONG, "not a Long constant") { initial ->
        initial.toLongOrNull()?.let {
            literalNumber(if (it == Long.MIN_VALUE) "Long.MIN_VALUE" else "${it}L")
        }
    },
    InitialFormat(UBYTE, "not a UByte constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in 0..UByte.MAX_VALUE.toLong() }
            ?.let { literalNumber("$it.toUByte()") }
    },
    InitialFormat(USHORT, "not a UShort constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in 0..UShort.MAX_VALUE.toLong() }
            ?.let { literalNumber("$it.toUShort()") }
    },
    InitialFormat(UINT, "not a UInt constant") { initial ->
        initial.toLongOrNull()?.takeIf { it in 0..UInt.MAX_VALUE.toLong() }
            ?.let { literalNumber("${it}u") }
    },
    InitialFormat(ULONG, "not a ULong constant") { initial ->
        initial.toULongOrNull()?.let { literalNumber("${it}uL") }
    },
    InitialFormat(FLOAT, "not a finite Float constant") { initial ->
        initial.toFloatOrNull()?.takeIf(Float::isFinite)
            ?.let { literalNumber("${it}f") }
    },
    InitialFormat(DOUBLE, "not a finite Double constant") { initial ->
        initial.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?.let { literalNumber(formatFloatingPoint(it)) }
    },
    InitialFormat(BOOLEAN, "not a Boolean constant") { initial ->
        when (initial) {
            "true", "false" -> literalNumber(initial)
            else -> null
        }
    },
    InitialFormat(CHAR, "not a single character") { initial ->
        if (initial.length == 1) CodeBlock.of("'%L'", escapeCharLiteral(initial[0])) else null
    },
    InitialFormat(STRING, "not a String constant") { literalString(it) },
)

/** Renders [text] as a literal expression. */
internal fun literalNumber(text: String): CodeBlock = CodeBlock.of("%L", text)

/**
 * Renders [value] as a Kotlin string literal. KotlinPoet's `%S` format
 * cannot carry a surrogate code unit through the generated UTF-8 file, so
 * a value that contains one is rendered with explicit escapes instead,
 * which keeps the value verbatim.
 */
internal fun literalString(value: String): CodeBlock =
    if (value.none { it.isHighSurrogate() || it.isLowSurrogate() }) {
        CodeBlock.of("%S", value)
    } else {
        CodeBlock.of("%L", escapedStringLiteral(value))
    }

/**
 * Renders [value] as a double-quoted Kotlin string literal, escaping the
 * characters that cannot travel through the generated file verbatim.
 */
private fun escapedStringLiteral(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> if (Character.getType(character) in escapedCharacterTypes) {
                append(unicodeEscape(character))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

/** Renders [character] as a `\uXXXX` escape. */
private fun unicodeEscape(character: Char): String =
    "\\u${character.code.toString(16).padStart(4, '0')}"

/** Renders a floating point value so that it keeps its floating point type. */
private fun formatFloatingPoint(value: Double): String {
    val rendered = value.toString()
    return if (rendered.any { it == '.' || it == 'e' || it == 'E' }) rendered else "$rendered.0"
}

/**
 * Renders [character] as the body of a Kotlin character literal. Control
 * characters, surrogates, and other characters that would either not
 * survive the UTF-8 encoding of the generated file or stay invisible in
 * its source are written as `\uXXXX` escapes.
 */
internal fun escapeCharLiteral(character: Char): String = when (character) {
    '\\' -> "\\\\"
    '\'' -> "\\'"
    '\n' -> "\\n"
    '\r' -> "\\r"
    '\t' -> "\\t"
    '$' -> "\\$"
    else -> if (Character.getType(character) in escapedCharacterTypes) {
        unicodeEscape(character)
    } else {
        character.toString()
    }
}

/**
 * The character categories that [escapeCharLiteral] escapes: control and
 * unassigned characters, surrogates that a UTF-8 file cannot carry, and
 * the invisible format and separator characters.
 */
private val escapedCharacterTypes = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.SURROGATE.toInt(),
    Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(),
    Character.PRIVATE_USE.toInt(),
    Character.UNASSIGNED.toInt(),
)
