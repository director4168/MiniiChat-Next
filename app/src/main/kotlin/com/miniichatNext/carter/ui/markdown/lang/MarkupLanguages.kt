package com.miniichatNext.carter.ui.markdown.lang

internal fun jsonRules(): LangRules = LangRules(
    keywords = emptySet(),
    types = setOf("true", "false", "null"),
    functions = emptySet(),
    lineComment = null,
    blockCommentOpen = null,
    blockCommentClose = null,
    stringDelims = listOf('"')
)

internal fun xmlRules(): LangRules = LangRules(
    keywords = emptySet(),
    types = emptySet(),
    functions = emptySet(),
    lineComment = null,
    blockCommentOpen = "<!--",
    blockCommentClose = "-->",
    stringDelims = listOf('"', '\'')
)

internal fun htmlRules(): LangRules = LangRules(
    keywords = setOf(
        "div", "span", "p", "a", "img", "input", "button", "form",
        "label", "select", "option", "table", "tr", "td", "th",
        "thead", "tbody", "ul", "ol", "li", "h1", "h2", "h3", "h4",
        "h5", "h6", "p", "br", "hr", "head", "body", "html",
        "script", "style", "link", "meta", "title",
    ),
    types = emptySet(),
    functions = emptySet(),
    lineComment = null,
    blockCommentOpen = "<!--",
    blockCommentClose = "-->",
    stringDelims = listOf('"', '\'')
)

internal fun yamlRules(): LangRules = LangRules(
    keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
    types = emptySet(),
    functions = emptySet(),
    lineComment = "#",
    blockCommentOpen = null,
    blockCommentClose = null,
    stringDelims = listOf('"', '\'')
)

internal fun markdownRules(): LangRules = LangRules(
    keywords = emptySet(),
    types = emptySet(),
    functions = emptySet(),
    lineComment = null,
    blockCommentOpen = null,
    blockCommentClose = null,
    stringDelims = listOf()
)
