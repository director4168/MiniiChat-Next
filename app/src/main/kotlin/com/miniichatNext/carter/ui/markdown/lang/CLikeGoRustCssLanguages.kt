package com.miniichatNext.carter.ui.markdown.lang

/** C/C++/Go/Rust/CSS语法高亮规则 */
internal fun cRules(): LangRules = LangRules(
    keywords = setOf(
        "auto", "break", "case", "const", "continue", "default", "do",
        "else", "enum", "extern", "for", "goto", "if", "register",
        "return", "sizeof", "static", "struct", "switch", "typedef",
        "union", "volatile", "while", "inline", "restrict",
        "true", "false", "NULL",
    ),
    types = setOf(
        "char", "double", "float", "int", "long", "short", "signed",
        "unsigned", "void", "size_t", "ssize_t", "int8_t", "int16_t",
        "int32_t", "int64_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "bool", "FILE",
    ),
    functions = setOf(
        "printf", "scanf", "malloc", "calloc", "realloc", "free",
        "memcpy", "memset", "strlen", "strcpy", "strcmp", "fopen",
        "fclose", "fread", "fwrite", "exit", "main",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\''),
)

internal fun cppRules(): LangRules = LangRules(
    keywords = setOf(
        "alignas", "alignof", "and", "asm", "auto", "break", "case",
        "catch", "class", "const", "constexpr", "const_cast", "continue",
        "decltype", "default", "delete", "do", "dynamic_cast", "else",
        "enum", "explicit", "export", "extern", "false", "for", "friend",
        "goto", "if", "inline", "mutable", "namespace", "new", "noexcept",
        "not", "nullptr", "operator", "or", "override", "private",
        "protected", "public", "register", "return", "sizeof", "static",
        "static_cast", "struct", "switch", "template", "this", "throw",
        "true", "try", "typedef", "typeid", "typename", "union", "using",
        "virtual", "volatile", "while", "final", "concept", "requires",
        "co_await", "co_return", "co_yield",
    ),
    types = setOf(
        "bool", "char", "char8_t", "char16_t", "char32_t", "double",
        "float", "int", "long", "short", "signed", "unsigned", "void",
        "wchar_t", "size_t", "string", "vector", "map", "set", "unordered_map",
        "unordered_set", "array", "pair", "tuple", "optional", "variant",
        "unique_ptr", "shared_ptr", "weak_ptr", "function", "auto",
        "std", "uint8_t", "int32_t", "int64_t",
    ),
    functions = setOf(
        "cout", "cin", "endl", "push_back", "emplace_back", "size",
        "empty", "clear", "find", "insert", "erase", "begin", "end",
        "sort", "swap", "resize", "reserve", "data", "c_str",
        "std", "make_unique", "make_shared", "move", "forward",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\''),
)

internal fun goRules(): LangRules = LangRules(
    keywords = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer",
        "else", "fallthrough", "for", "func", "go", "goto", "if",
        "import", "interface", "map", "package", "range", "return",
        "select", "struct", "switch", "type", "var", "true", "false",
        "nil", "iota",
    ),
    types = setOf(
        "bool", "byte", "complex64", "complex128", "error", "float32",
        "float64", "int", "int8", "int16", "int32", "int64", "rune",
        "string", "uint", "uint8", "uint16", "uint32", "uint64",
        "uintptr", "any", "comparable",
    ),
    functions = setOf(
        "append", "cap", "close", "copy", "delete", "len", "make",
        "new", "panic", "print", "println", "recover",
        "Println", "Printf", "Sprintf", "Errorf", "Fatalf",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\'', '`')
)

internal fun rustRules(): LangRules = LangRules(
    keywords = setOf(
        "as", "async", "await", "break", "const", "continue", "crate",
        "dyn", "else", "enum", "extern", "false", "fn", "for", "if",
        "impl", "in", "let", "loop", "match", "mod", "move", "mut",
        "pub", "ref", "return", "self", "Self", "static", "struct",
        "super", "trait", "true", "type", "unsafe", "use", "where",
        "while", "union",
    ),
    types = setOf(
        "bool", "char", "f32", "f64", "i8", "i16", "i32", "i64", "i128",
        "isize", "str", "u8", "u16", "u32", "u64", "u128", "usize",
        "String", "Vec", "Option", "Result", "Box", "Rc", "Arc", "RefCell",
        "HashMap", "HashSet", "BTreeMap", "BTreeSet", "VecDeque",
    ),
    functions = setOf(
        "println", "print", "eprintln", "eprint", "format", "panic",
        "vec", "assert", "assert_eq", "unwrap", "expect", "clone",
        "to_string", "push", "pop", "len", "iter", "map", "filter",
        "collect", "unwrap_or", "ok", "err", "some", "none",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"')
)

internal fun cssRules(): LangRules = LangRules(
    keywords = setOf(
        "important", "media", "supports", "keyframes", "import", "font-face",
        "root", "screen", "print", "hover", "focus", "active", "visited",
        "before", "after", "first-child", "last-child", "not", "is", "where",
        "has", "from", "to", "only", "and", "or", "var", "calc", "min", "max",
        "clamp", "url", "linear-gradient", "radial-gradient",
    ),
    types = setOf(
        "color", "background", "background-color", "background-image",
        "border", "border-radius", "border-color", "margin", "margin-top",
        "padding", "padding-top", "width", "height", "min-width", "max-width",
        "display", "flex", "grid", "position", "top", "left", "right", "bottom",
        "font", "font-size", "font-weight", "font-family", "line-height",
        "text-align", "text-decoration", "color", "opacity", "overflow",
        "z-index", "cursor", "transition", "transform", "animation",
        "box-shadow", "flex-direction", "justify-content", "align-items",
        "gap", "aspect-ratio", "object-fit", "pointer-events", "user-select",
    ),
    functions = setOf(
        "rgb", "rgba", "hsl", "hsla", "var", "calc", "url", "attr",
        "linear-gradient", "radial-gradient", "conic-gradient",
        "translate", "translateX", "translateY", "rotate", "scale", "skew",
        "cubic-bezier", "steps", "repeat", "minmax", "fit-content",
    ),
    lineComment = null,
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\''),
    allowHexColors = true,
)

internal fun csharpRules(): LangRules = LangRules(
    keywords = setOf(
        "abstract", "as", "async", "await", "base", "break", "case",
        "catch", "checked", "class", "const", "continue", "default",
        "delegate", "do", "else", "enum", "event", "explicit", "extern",
        "false", "finally", "fixed", "for", "foreach", "get", "goto",
        "if", "implicit", "in", "init", "interface", "internal", "is",
        "lock", "namespace", "new", "null", "operator", "out", "override",
        "params", "partial", "private", "protected", "public", "readonly",
        "record", "ref", "return", "sealed", "set", "sizeof", "static",
        "struct", "switch", "this", "throw", "true", "try", "typeof",
        "unchecked", "unsafe", "using", "virtual", "void", "volatile",
        "when", "where", "while", "yield", "required", "global",
    ),
    types = setOf(
        "bool", "byte", "char", "decimal", "double", "dynamic", "float",
        "int", "long", "object", "sbyte", "short", "string", "uint",
        "ulong", "ushort", "var", "String", "Int32", "Int64", "Boolean",
        "DateTime", "Task", "List", "Dictionary", "IEnumerable",
        "IList", "HashSet", "Nullable", "Guid", "Exception",
    ),
    functions = setOf(
        "Console", "WriteLine", "Write", "ReadLine", "ToString",
        "Equals", "GetHashCode", "GetType", "Append", "Add", "Remove",
        "Contains", "Select", "Where", "ToList", "ToArray", "FirstOrDefault",
        "Any", "All", "Count", "OrderBy", "GroupBy", "Task", "Run",
    ),
    lineComment = "//",
    blockCommentOpen = "/*",
    blockCommentClose = "*/",
    stringDelims = listOf('"', '\''),
)
