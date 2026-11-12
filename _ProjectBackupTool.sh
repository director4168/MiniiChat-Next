#!/system/bin/sh
set -eu

# 需要打包的目录
INCLUDE_DIRS="app gradle"

# 忽略打包的文件
EXCLUDE_FILES="BACKLOG.md CHANGELOG.md workspace.json .gitignore .clangd .clang-tidy *.zip"

# 忽略打包的子目录内文件/文件夹
EXCLUDE_PATHS="app/build app/.cxx"


find_project_root() {
    _d="$1"
    while [ -n "$_d" ] && [ "$_d" != "/" ]; do
        if [ -d "$_d/app" ] && [ -d "$_d/gradle" ]; then
            echo "$_d"
            return 0
        fi
        _d="$(dirname "$_d")"
    done
    return 1
}
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SCRIPT_DIR="$(pwd)"

if [ "$#" -ge 1 ] && [ -n "$1" ]; then
    PROJECT_DIR="$1"
else
    PROJECT_DIR="$(find_project_root "$SCRIPT_DIR" || true)"
    [ -z "$PROJECT_DIR" ] && PROJECT_DIR="$(find_project_root "$(pwd)" || true)"
    [ -z "$PROJECT_DIR" ] && PROJECT_DIR="$SCRIPT_DIR"
fi

if [ "$#" -ge 2 ]; then
    OUTPUT_DIR="$2"
else
    OUTPUT_DIR="$SCRIPT_DIR"
fi

cd "$PROJECT_DIR" 2>/dev/null || {
    echo -e "\033[31m错误：项目目录不存在 -> $PROJECT_DIR\033[0m" >&2
    exit 1
}

mkdir -p "$OUTPUT_DIR"

PROJECT_NAME="$(basename "$(pwd)")"

BUILD_FILE="app/build.gradle.kts"
VERSION_NAME=""
VERSION_CODE=""
if [ -f "$BUILD_FILE" ]; then
    VERSION_NAME="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$BUILD_FILE" | head -n1 | sed -nE 's/.*=[[:space:]]*"([^"]*)".*/\1/p' | tr -d '\r')"
    VERSION_CODE="$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$BUILD_FILE" | head -n1 | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/' | tr -d '\r')"
fi

STAMP="$(date +%Y%m%d%H%M)"

ARCHIVE_BASE="$PROJECT_NAME"
if [ -n "$VERSION_NAME" ]; then ARCHIVE_BASE="${ARCHIVE_BASE}_${VERSION_NAME}"; fi
if [ -n "$VERSION_CODE" ]; then ARCHIVE_BASE="${ARCHIVE_BASE}_${VERSION_CODE}"; fi

ARCHIVE_NAME="${ARCHIVE_BASE}_${STAMP}.zip"
ARCHIVE_PATH="${OUTPUT_DIR}/${ARCHIVE_NAME}"

N=2
while [ -e "$ARCHIVE_PATH" ]; do
    ARCHIVE_NAME="${ARCHIVE_BASE}_${STAMP}_${N}.zip"
    ARCHIVE_PATH="${OUTPUT_DIR}/${ARCHIVE_NAME}"
    N=$((N+1))
done

echo "项目名称 : $PROJECT_NAME"
echo "项目目录 : $(pwd)"
if [ -n "$VERSION_NAME" ] || [ -n "$VERSION_CODE" ]; then
    echo -e "版本信息 : ${VERSION_NAME:-未知} (${VERSION_CODE:-未知})"
else
    echo -e "\033[33m版本信息 : 未检测到（$BUILD_FILE 缺失或无 versionName/versionCode）\033[0m" >&2
fi
echo "输出文件 : $ARCHIVE_PATH"
echo ""

ITEMS=""

for d in $INCLUDE_DIRS; do
    [ -d "$d" ] && ITEMS="$ITEMS $d"
done

for f in .[!.]* *; do
    [ -f "$f" ] || continue
    case "$f" in
        ${ARCHIVE_BASE}_*.zip|MiniiChat-Next_*.zip) continue ;;
    esac
    skip=0
    set -f
    for pat in $EXCLUDE_FILES; do
        case "$f" in
            $pat) skip=1; break ;;
        esac
    done
    set +f
    [ "$skip" = "1" ] && continue
    ITEMS="$ITEMS $f"
done

ZIP_EXCLUDES=""
set -f
for p in $EXCLUDE_PATHS; do
    p="${p#./}"
    p="${p%/}"
    [ -z "$p" ] && continue
    ZIP_EXCLUDES="$ZIP_EXCLUDES ${p} ${p}/*"
done
set +f

if [ -z "$ITEMS" ]; then
    echo -e "\033[31m错误：没有可备份的内容（app/ 与 gradle/ 都不存在？）\033[0m" >&2
    exit 1
fi

echo "打包内容 :"
for item in $ITEMS; do
    if [ -d "$item" ]; then
        echo "  [目录] $item/"
    else
        echo "  [文件] $item"
    fi
done
if [ -n "$ZIP_EXCLUDES" ]; then
    echo "忽略内容 : $EXCLUDE_PATHS"
fi
echo ""

if command -v zip >/dev/null 2>&1; then
    set -f
    if [ -n "$ZIP_EXCLUDES" ]; then
        zip -r -q "$ARCHIVE_PATH" $ITEMS -x $ZIP_EXCLUDES
    else
        zip -r -q "$ARCHIVE_PATH" $ITEMS
    fi
    set +f
elif command -v python3 >/dev/null 2>&1; then
    set -f
    BACKUP_EXCLUDE_PATHS="$EXCLUDE_PATHS" python3 - "$ARCHIVE_PATH" $ITEMS <<'PYEOF'
import fnmatch
import os
import sys
import zipfile

archive = sys.argv[1]
items = sys.argv[2:]

patterns = []
for raw in os.environ.get("BACKUP_EXCLUDE_PATHS", "").split():
    p = raw
    if p.startswith("./"):
        p = p[2:]
    if p.endswith("/"):
        p = p[:-1]
    if p:
        patterns.append(p)


def excluded(rel):
    rel = rel.rstrip("/")
    for p in patterns:
        if rel == p or rel.startswith(p + "/"):
            return True
        if fnmatch.fnmatch(rel, p) or fnmatch.fnmatch(rel, p + "/*"):
            return True
    return False


with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zf:
    for item in items:
        if os.path.isdir(item):
            if excluded(item):
                continue
            for root, dirs, files in os.walk(item):
                dirs[:] = sorted(d for d in dirs if not excluded(os.path.join(root, d)))
                zf.write(root, root + "/")
                for name in sorted(files):
                    path = os.path.join(root, name)
                    if not excluded(path):
                        zf.write(path)
        elif not excluded(item):
            zf.write(item)
PYEOF
    set +f
else
    echo -e "\033[31m错误：系统里没有zip，也没有python3，无法打包\033[0m" >&2
    exit 1
fi

if [ -f "$ARCHIVE_PATH" ]; then
    SIZE="$(du -h "$ARCHIVE_PATH" 2>/dev/null | cut -f1)"
    COUNT="$(unzip -l "$ARCHIVE_PATH" 2>/dev/null | tail -1 | awk '{print $2}')"
    echo -e "\033[32m备份完成\033[0m"
    echo "   文件：$ARCHIVE_PATH"
    [ -n "$SIZE" ]  && echo "   大小：$SIZE"
    [ -n "$COUNT" ] && echo "   条目：$COUNT"
else
    echo -e "\033[31m错误：打包失败\033[0m" >&2
    exit 1
fi