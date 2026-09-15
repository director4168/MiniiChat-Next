package com.miniichatNext.carter.ui.markdown.lang

internal val ALL_LANGUAGES: Map<String, LangRules> = mapOf(
    "kotlin" to kotlinRules(),
    "java" to javaRules(),
    "javascript" to javascriptRules(),
    "js" to javascriptRules(),
    "typescript" to typescriptRules(),
    "ts" to typescriptRules(),
    "python" to pythonRules(),
    "py" to pythonRules(),
    "json" to jsonRules(),
    "yaml" to yamlRules(),
    "yml" to yamlRules(),
    "xml" to xmlRules(),
    "html" to htmlRules(),
    "bash" to bashRules(),
    "sh" to bashRules(),
    "shell" to bashRules(),
    "sql" to sqlRules(),
    "markdown" to markdownRules(),
    "md" to markdownRules(),
)
