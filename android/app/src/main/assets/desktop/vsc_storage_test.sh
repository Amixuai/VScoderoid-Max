#!/bin/bash
# Storage capability test (brief, Appendix A).
#
#   vsc_storage_test.sh <shared-test-folder> <private-test-folder>
#
# Runs the same checks in both folders and prints one line per check:
#   STORAGE-<where>-<check>|PASS|detail
#   STORAGE-<where>-<check>|FAIL|detail
#   STORAGE-<where>-<check>|SKIP|reason
# <where> is SHARED or PRIVATE. Run it inside the Debian system (so the tools the
# checks use are the ones a project would use), with the shared folder on shared
# storage (for example the bound workspace, /root/workspace/.vsc-storage-test) and
# the private folder in the guest's own tree (for example /root/.vsc-storage-test).
# Paste the output into the test report; each language row of brief 13.4 is set
# from it. A check whose tool is not installed is SKIP, never PASS.

set -u
SHARED=${1:?usage: vsc_storage_test.sh <shared-folder> <private-folder>}
PRIVATE=${2:?usage: vsc_storage_test.sh <shared-folder> <private-folder>}

emit() { printf 'STORAGE-%s-%s|%s|%s\n' "$WHERE" "$1" "$2" "$3"; }

# have_tool <name> ; returns 0 when present
have_tool() { command -v "$1" >/dev/null 2>&1; }

# run <seconds> <command...> ; output captured in $OUT, status in $RC
run() {
    local seconds=$1; shift
    if have_tool timeout; then
        OUT=$(timeout "$seconds" "$@" 2>&1); RC=$?
    else
        OUT=$("$@" 2>&1); RC=$?
    fi
    OUT=$(printf '%s' "$OUT" | tr '\n' ' ' | cut -c1-200)
}

verdict() { # verdict <check> <rc> <detail-on-fail> [detail-on-pass]
    if [ "$2" -eq 0 ]; then emit "$1" PASS "${4:-ok}"; else emit "$1" FAIL "$3"; fi
}

t_write_read() {
    printf 'hello' > "$D/f.txt" 2>/dev/null && [ "$(cat "$D/f.txt" 2>/dev/null)" = hello ]
    verdict file-write-read $? "could not write and read back a file"
}
t_symlink() {
    run 20 ln -s f.txt "$D/link.txt"
    if [ $RC -eq 0 ] && [ "$(cat "$D/link.txt" 2>/dev/null)" = hello ]; then emit symlink PASS ok
    else emit symlink FAIL "${OUT:-link does not read back}"; fi
}
t_hardlink() {
    run 20 ln "$D/f.txt" "$D/hard.txt"
    if [ $RC -eq 0 ] && [ "$(cat "$D/hard.txt" 2>/dev/null)" = hello ]; then emit hardlink PASS ok
    else emit hardlink FAIL "${OUT:-hard link does not read back}"; fi
}
t_chmod_exec() {
    printf '#!/bin/sh\necho ran\n' > "$D/s.sh"
    chmod +x "$D/s.sh" 2>/dev/null
    run 20 "$D/s.sh"
    if [ $RC -eq 0 ] && [ "$OUT" = "ran" ]; then emit chmod-exec-script PASS ok
    else emit chmod-exec-script FAIL "rc=$RC ${OUT}"; fi
}
t_compiled() {
    local cc=""
    have_tool gcc && cc=gcc
    [ -z "$cc" ] && have_tool cc && cc=cc
    if [ -z "$cc" ]; then emit compiled-program SKIP "no C compiler (apt-get install build-essential)"; return; fi
    printf '#include <stdio.h>\nint main(void){puts("c-ok");return 0;}\n' > "$D/p.c"
    run 120 $cc -o "$D/p" "$D/p.c"
    if [ $RC -ne 0 ]; then emit compiled-program FAIL "compile: $OUT"; return; fi
    run 20 "$D/p"
    if [ $RC -eq 0 ] && [ "$OUT" = "c-ok" ]; then emit compiled-program PASS ok
    else emit compiled-program FAIL "run: rc=$RC $OUT"; fi
}
t_many_files() {
    mkdir -p "$D/many/a" "$D/many/b" || { emit move-delete-1000 FAIL "mkdir"; return; }
    local i=0
    while [ $i -lt 1000 ]; do : > "$D/many/a/f$i" || break; i=$((i + 1)); done
    if [ $i -lt 1000 ]; then emit move-delete-1000 FAIL "created only $i files"; return; fi
    local moved=0
    for f in "$D"/many/a/f*; do mv "$f" "$D/many/b/" 2>/dev/null && moved=$((moved + 1)); done
    rm -rf "$D/many/b" 2>/dev/null
    if [ "$moved" -eq 1000 ] && [ ! -e "$D/many/b" ]; then emit move-delete-1000 PASS "moved and deleted 1000"
    else emit move-delete-1000 FAIL "moved $moved, folder left: $([ -e "$D/many/b" ] && echo yes || echo no)"; fi
}
t_venv() {
    if ! have_tool python3; then emit python-venv SKIP "no python3"; emit python-venv-copies SKIP "no python3"; return; fi
    run 180 python3 -m venv "$D/venv"
    if [ $RC -eq 0 ] && [ -x "$D/venv/bin/python" ] && "$D/venv/bin/python" -c 'print(1)' >/dev/null 2>&1; then emit python-venv PASS ok
    else emit python-venv FAIL "$OUT"; fi
    run 180 python3 -m venv --copies "$D/venv2"
    if [ $RC -eq 0 ] && [ -x "$D/venv2/bin/python" ] && "$D/venv2/bin/python" -c 'print(1)' >/dev/null 2>&1; then emit python-venv-copies PASS ok
    else emit python-venv-copies FAIL "$OUT"; fi
}
t_npm() {
    if ! have_tool npm; then emit npm-install-local-bin SKIP "no npm (apt-get install nodejs npm)"; return; fi
    mkdir -p "$D/npm/pkg" "$D/npm/app"
    printf '{"name":"vscpkg","version":"1.0.0","bin":{"vscpkg":"cli.js"}}\n' > "$D/npm/pkg/package.json"
    printf '#!/usr/bin/env node\nconsole.log("npm-ok");\n' > "$D/npm/pkg/cli.js"
    printf '{"name":"vscapp","version":"1.0.0"}\n' > "$D/npm/app/package.json"
    run 240 bash -c "cd '$D/npm/app' && npm install --no-audit --no-fund '$D/npm/pkg'"
    if [ $RC -ne 0 ]; then emit npm-install-local-bin FAIL "install: $OUT"; return; fi
    run 30 bash -c "cd '$D/npm/app' && ./node_modules/.bin/vscpkg"
    if [ $RC -eq 0 ] && [ "$OUT" = "npm-ok" ]; then emit npm-install-local-bin PASS ok
    else emit npm-install-local-bin FAIL "run .bin: rc=$RC $OUT"; fi
}
t_rust() {
    if ! have_tool cargo; then emit rust-build-run SKIP "no cargo (apt-get install cargo)"; return; fi
    mkdir -p "$D/rs/src"
    printf '[package]\nname = "vscrs"\nversion = "0.1.0"\nedition = "2021"\n' > "$D/rs/Cargo.toml"
    printf 'fn main() { println!("rust-ok"); }\n' > "$D/rs/src/main.rs"
    run 600 bash -c "cd '$D/rs' && cargo build --offline"
    if [ $RC -ne 0 ]; then emit rust-build-run FAIL "build: $OUT"; return; fi
    run 30 "$D/rs/target/debug/vscrs"
    if [ $RC -eq 0 ] && [ "$OUT" = "rust-ok" ]; then emit rust-build-run PASS "target/debug runs"
    else emit rust-build-run FAIL "run: rc=$RC $OUT"; fi
}
t_go() {
    if ! have_tool go; then emit go-build-run SKIP "no go (apt-get install golang)"; return; fi
    mkdir -p "$D/go"
    printf 'module vscgo\n\ngo 1.20\n' > "$D/go/go.mod"
    printf 'package main\nimport "fmt"\nfunc main() { fmt.Println("go-ok") }\n' > "$D/go/main.go"
    run 600 bash -c "cd '$D/go' && GOFLAGS=-mod=mod go build -o vscgo ."
    if [ $RC -ne 0 ]; then emit go-build-run FAIL "build: $OUT"; return; fi
    run 30 "$D/go/vscgo"
    if [ $RC -eq 0 ] && [ "$OUT" = "go-ok" ]; then emit go-build-run PASS ok
    else emit go-build-run FAIL "run: rc=$RC $OUT"; fi
}
t_sqlite() {
    if ! have_tool python3; then emit sqlite-wal SKIP "no python3"; return; fi
    run 60 python3 - "$D/db.sqlite" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
mode = c.execute("PRAGMA journal_mode=WAL").fetchone()[0]
c.execute("create table t(x)"); c.execute("insert into t values (1)"); c.commit()
r = sqlite3.connect(sys.argv[1]).execute("select count(*) from t").fetchone()[0]
print("mode=%s rows=%s" % (mode, r))
sys.exit(0 if (mode == "wal" and r == 1) else 3)
PY
    if [ $RC -eq 0 ]; then emit sqlite-wal PASS "$OUT"; else emit sqlite-wal FAIL "$OUT"; fi
}

for pair in "SHARED:$SHARED" "PRIVATE:$PRIVATE"; do
    WHERE=${pair%%:*}
    D=${pair#*:}
    rm -rf "$D" 2>/dev/null
    if ! mkdir -p "$D" 2>/dev/null; then emit setup FAIL "cannot create $D"; continue; fi
    t_write_read; t_symlink; t_hardlink; t_chmod_exec; t_compiled
    t_many_files; t_venv; t_npm; t_rust; t_go; t_sqlite
    rm -rf "$D" 2>/dev/null
done
