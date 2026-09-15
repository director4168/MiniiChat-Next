package com.miniichatNext.carter.ui.markdown.lang

internal fun javascriptRules(): LangRules = LangRules(
    keywords = setOf(
        "var", "let", "const", "function", "return", "if", "else",
        "for", "while", "do", "switch", "case", "break", "continue",
        "new", "this", "class", "extends", "import", "export",
        "from", "as", "default", "async", "await", "yield", "try",
        "catch", "finally", "throw", "typeof", "instanceof", "in",
        "of", "true", "false", "null", "undefined",
    ),
    types = setOf(
        "Array", "Object", "String", "Number", "Boolean", "Promise",
        "Map", "Set", "Date", "RegExp", "Function", "Symbol",
        "Error", "JSON",
    ),
    functions = setOf(
        "console", "log", "warn", "error", "map", "filter", "find",
        "forEach", "reduce", "push", "pop", "shift", "unshift",
        "slice", "splice", "join", "split", "trim", "replace",
        "fetch", "then", "catch", "JSON.stringify", "JSON.parse",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\'', '`')
)

internal fun typescriptRules(): LangRules = LangRules(
    keywords = setOf(
        "var", "let", "const", "function", "return", "if", "else",
        "for", "while", "do", "switch", "case", "break", "continue",
        "new", "this", "super", "class", "extends", "implements",
        "interface", "type", "enum", "abstract", "import", "export",
        "from", "as", "default", "async", "await", "yield", "try",
        "catch", "finally", "throw", "typeof", "instanceof", "in",
        "of", "true", "false", "null", "undefined", "namespace",
        "declare", "readonly", "public", "private", "protected",
        "satisfies", "keyof", "infer",
    ),
    types = setOf(
        "Array", "Object", "String", "Number", "Boolean", "Promise",
        "Map", "Set", "Date", "RegExp", "Function", "Symbol",
        "Error", "JSON", "Partial", "Required", "Readonly",
        "Record", "Pick", "Omit", "Exclude", "Extract",
    ),
    functions = setOf(
        "console", "log", "warn", "error", "map", "filter", "find",
        "forEach", "reduce", "push", "pop", "fetch", "JSON.stringify",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\'', '`')
)

internal fun pythonRules(): LangRules = LangRules(
    keywords = setOf(
        "def", "class", "if", "elif", "else", "for", "while",
        "return", "import", "from", "as", "with", "try", "except",
        "finally", "raise", "pass", "continue", "break", "lambda",
        "yield", "async", "await", "global", "nonlocal", "True",
        "False", "None", "and", "or", "not", "in", "is",
    ),
    types = setOf(
        "int", "float", "str", "bool", "list", "dict", "tuple", "set",
        "bytes", "object", "type",
    ),
    functions = setOf(
        "print", "len", "range", "enumerate", "zip", "map", "filter",
        "sorted", "reversed", "sum", "min", "max", "abs", "round",
        "isinstance", "open", "input", "int", "str", "float", "list",
        "dict", "set", "tuple", "type",
    ),
    lineComment = "#",
    blockCommentOpen = null,
    blockCommentClose = null,
    stringDelims = listOf('"', '\'')
)
