#!/usr/bin/env bash
# 架构边界自动校验（借鉴 GodMode-Pro verify-architecture-boundary.ps1）。
#
# 固化 AGENTS.md 里 phase 1-3 已完成的人工边界约定，防回归：
#   1. shared/ 不得依赖 host/ 或 target/
#   2. target/ 不得依赖 host/
#   3. host/ 不得直接依赖 target/（宿主只走 data/ 冻结壳）
#   4. libxposed import 仅限 target/hook/** 与 host/service/**
#   5. 不得出现 legacy de.robv.android.xposed import（已迁移 libxposed API 102）
#   6. 协议冻结 FQCN 文件必须存在（Manifest 组件 / Xposed 入口）
#
# 用法：仓库根目录执行  bash scripts/verify-architecture.sh
# 退出码 0 = 全部通过；非 0 = 列出全部违例。

set -u
cd "$(dirname "$0")/.." || exit 1

SRC="app/src/main/java/com/godviewer/app"
FAILURES=()

# 逐条打印违例并记入总数
assert_no_import() {
    local desc="$1" roots="$2" pattern="$3"
    local hits
    hits=$(grep -rnE "$pattern" $roots --include='*.kt' 2>/dev/null || true)
    if [ -n "$hits" ]; then
        FAILURES+=("$desc")
        echo "FAIL: $desc"
        echo "$hits" | sed 's/^/    /'
    fi
}

echo "== 架构边界校验 =="
echo "1/6 shared/ 不得依赖 host/ 或 target/"
assert_no_import \
    "shared/ imports host or target" \
    "$SRC/shared" \
    '^import com\.godviewer\.app\.(host|target)\.'

echo "2/6 target/ 不得依赖 host/"
assert_no_import \
    "target/ imports host" \
    "$SRC/target" \
    '^import com\.godviewer\.app\.host\.'

echo "3/6 host/ 不得直接依赖 target/（只允许 data/ 冻结壳转发）"
assert_no_import \
    "host/ imports target" \
    "$SRC/host" \
    '^import com\.godviewer\.app\.target\.'

echo "4/6 libxposed import 仅限 target/hook/** 与 host/service/**"
libxposed_hits=$(grep -rln 'import io\.github\.libxposed' app/src/main/java --include='*.kt' 2>/dev/null | grep -vE 'target/hook/|host/service/' || true)
if [ -n "$libxposed_hits" ]; then
    FAILURES+=("libxposed import outside target/hook and host/service")
    echo "FAIL: libxposed import outside target/hook and host/service"
    echo "$libxposed_hits" | sed 's/^/    /'
fi

echo "5/6 不得出现 legacy de.robv.android.xposed import"
assert_no_import \
    "legacy de.robv.android.xposed import found" \
    "$SRC" \
    '^import de\.robv\.android\.xposed\.'

echo "6/6 协议冻结 FQCN 文件存在性"
for f in \
    "$SRC/data/RuleMirrorReceiver.kt" \
    "$SRC/data/HostControlReceiver.kt" \
    "$SRC/data/HostPrefsProvider.kt" \
    "$SRC/util/ModuleStatus.kt"; do
    if [ ! -f "$f" ]; then
        FAILURES+=("frozen FQCN missing: $f")
        echo "FAIL: frozen FQCN missing: $f"
    fi
done
if [ -f "app/src/main/resources/META-INF/xposed/java_init.list" ]; then
    if ! grep -q 'com.godviewer.app.target.hook.GodViewerModule' \
        "app/src/main/resources/META-INF/xposed/java_init.list"; then
        FAILURES+=("java_init.list entry class missing")
        echo "FAIL: java_init.list must contain com.godviewer.app.target.hook.GodViewerModule"
    fi
else
    FAILURES+=("META-INF/xposed/java_init.list missing")
    echo "FAIL: app/src/main/resources/META-INF/xposed/java_init.list missing"
fi

echo ""
if [ ${#FAILURES[@]} -eq 0 ]; then
    echo "OK: 全部边界检查通过"
    exit 0
fi
echo "FAILED: ${#FAILURES[@]} 类违例"
exit 1
