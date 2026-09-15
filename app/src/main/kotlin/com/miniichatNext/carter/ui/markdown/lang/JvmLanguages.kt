package com.miniichatNext.carter.ui.markdown.lang

internal fun kotlinRules(): LangRules = LangRules(
    keywords = setOf(
        "fun", "val", "var", "if", "else", "when", "for", "while", "do",
        "return", "class", "object", "interface", "data", "enum", "sealed",
        "private", "internal", "public", "protected", "open", "abstract",
        "override", "import", "package", "true", "false", "null", "this",
        "super", "throw", "try", "catch", "finally", "is", "as", "in", "out",
        "by", "companion", "operator", "infix", "tailrec", "inline",
        "suspend", "typealias", "vararg", "noinline", "crossinline",
        "init", "constructor", "where",
    ),
    types = setOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "Char",
        "Unit", "Any", "Nothing", "List", "MutableList", "Array",
        "IntArray", "Map", "MutableMap", "Set", "MutableSet",
        "Pair", "Triple", "Sequence", "Flow", "StateFlow",
    ),
    functions = setOf(
        "println", "print", "listOf", "mapOf", "setOf", "arrayOf",
        "mutableListOf", "mutableMapOf", "mutableSetOf", "filter",
        "map", "forEach", "first", "firstOrNull", "last", "size",
        "count", "any", "all", "none", "sumOf", "to", "also", "let",
        "run", "apply", "takeIf", "takeUnless", "with", "use",
        "repeat", "require", "check", "error", "TODO",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\'')
)

internal fun javaRules(): LangRules = LangRules(
    keywords = setOf(
        "public", "private", "protected", "class", "interface", "extends",
        "implements", "abstract", "final", "static", "void", "return",
        "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "new", "this", "super", "try", "catch", "finally",
        "throw", "throws", "import", "package", "true", "false", "null",
        "instanceof", "synchronized", "volatile", "transient", "native",
        "strictfp", "enum", "var", "sealed", "record", "yield",
        "non-sealed", "permits",
    ),
    types = setOf(
        "String", "Integer", "Long", "Float", "Double", "Boolean",
        "Character", "Byte", "Short", "Object", "Number", "List",
        "ArrayList", "Map", "HashMap", "Set", "HashSet", "Queue",
        "LinkedList", "Stack", "Vector", "Iterator", "Iterable",
        "Collection", "Stream", "Optional", "IntStream",
    ),
    functions = setOf(
        "println", "print", "printf", "format", "toString", "equals",
        "hashCode", "length", "size", "get", "set", "add", "remove",
        "contains", "stream", "forEach", "map", "filter", "collect",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\'')
)
