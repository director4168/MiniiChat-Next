package com.miniichatNext.carter.ui.markdown.lang

internal fun bashRules(): LangRules = LangRules(
    keywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while",
        "do", "done", "case", "esac", "function", "return",
        "in", "export", "local", "readonly", "declare", "set",
        "unset", "echo", "printf", "read", "exit", "trap",
        "true", "false",
    ),
    types = emptySet(),
    functions = emptySet(),
    lineComment = "#",
    blockCommentOpen = null,
    blockCommentClose = null,
    stringDelims = listOf('"', '\'')
)

internal fun sqlRules(): LangRules = LangRules(
    keywords = setOf(
        "select", "from", "where", "and", "or", "not", "null",
        "is", "in", "like", "between", "as", "on", "join",
        "inner", "left", "right", "outer", "full", "cross",
        "group", "by", "order", "having", "limit",
        "offset", "union", "all", "distinct", "insert", "into",
        "values", "update", "set", "delete", "create", "table",
        "drop", "alter", "add", "column", "primary", "key",
        "foreign", "references", "index", "view", "with",
        "case", "when", "then", "else", "end",
    ),
    types = setOf(
        "INT", "INTEGER", "VARCHAR", "CHAR", "TEXT", "DATE",
        "DATETIME", "TIMESTAMP", "BOOLEAN", "BIGINT", "DECIMAL",
        "FLOAT", "DOUBLE", "BLOB", "JSON",
    ),
    functions = setOf(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NOW",
        "DATEDIFF", "DATE_ADD", "DATE_SUB", "IFNULL", "NULLIF",
    ),
    lineComment = "--",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\'')
)
