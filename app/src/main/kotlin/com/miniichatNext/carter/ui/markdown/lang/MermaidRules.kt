package com.miniichatNext.carter.ui.markdown.lang

/** Mermaid图表语法高亮：图类型关键字+方向词+常用声明词 */
internal fun mermaidRules() = LangRules(
    keywords = setOf(
        // 图表类型
        "graph", "flowchart", "sequenceDiagram", "classDiagram", "stateDiagram-v2",
        "stateDiagram", "erDiagram", "journey", "gantt", "pie", "quadrantChart",
        "requirementDiagram", "gitgraph", "mindmap", "timeline", "zenuml", "sankey-beta",
        "xychart-beta", "block-beta", "packet-beta", "architecture-beta", "kanban",
        // 流程控制
        "subgraph", "end", "direction", "loop", "alt", "else", "opt", "par", "and",
        "note", "over", "right", "left", "activate", "deactivate", "autonumber",
        // 状态/类图
        "state", "class", "note for", "linkStyle", "classDef", "style", "click",
        "call", "href", "participant", "actor", "box", "rect", "crit", "break",
        // 甘特/饼图
        "dateFormat", "axisFormat", "section", "title", "excludes", "todayMarker",
        "taskName", "taskTag", "taskData",
    ),
    types = setOf(
        "LR", "RL", "TB", "BT", "TD", "H", "V",
    ),
    functions = emptySet(),
    lineComment = "%%",
    blockCommentOpen = null,
    blockCommentClose = null,
    stringDelims = listOf('"', '\''),
)
