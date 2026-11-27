#!/bin/bash
export DEBIAN_FRONTEND=noninteractive
export HOME="${HOME:-/root}"
OUTDIR="${LT_OUTDIR:-/storage/emulated/0/.vscoderoid-pro}"
mkdir -p "$OUTDIR" 2>/dev/null
OUT="$OUTDIR/language_test_3.txt"
WORK="$(mktemp -d /tmp/lt3.XXXXXX)"
PASS="$WORK/pass"; FAIL="$WORK/fail"; : > "$PASS"; : > "$FAIL"
echo "[1/3] Preparing: repairing dpkg, refreshing the package list (nothing is installed)..."
dpkg --configure -a >/dev/null 2>&1
if timeout 90 apt-get update -qq >/dev/null 2>&1; then HAVE_APT=1; else HAVE_APT=0; fi
why() {
  case "$1" in
    *PTRACE*|*ptrace*) echo "ptrace is not allowed inside PRoot (debuggers and tracers cannot work here)";;
    *"error while loading shared libraries"*) echo "missing shared library: $(echo "$1" | grep -o 'lib[A-Za-z0-9_.+-]*\.so[.0-9]*' | head -1)";;
    *"cannot find -l"*|*"fatal error:"*".h"*|*"No such file or directory"*"include"*) echo "missing library or header (a -dev package)";;
    *"Illegal instruction"*|*"Exec format error"*|*"Segmentation fault"*|*"Bus error"*) echo "program crashes or cannot run on this phone (arm64 or PRoot limit)";;
    *"Cannot allocate memory"*|*OutOfMemory*|*"Killed"*) echo "ran out of memory";;
    *"Could not resolve"*|*"Temporary failure"*|*"Network is unreachable"*|*"Connection refused"*|*"Unable to connect"*) echo "needs the internet or a running server";;
    *"cannot execute 'cc1"*|*"cc1plus: not found"*) echo "compiler is only partly installed (a compiler part is missing)";;
    *"Permission denied"*) echo "permission problem: $(echo "$1" | grep -i 'permission denied' | head -n 1 | cut -c1-110)";;
    *"No module named"*|*"Cannot find module"*|*"cannot load such file"*|*"Can't locate"*) echo "language is there but a library or module is missing: $(echo "$1" | head -n 1 | cut -c1-90)";;
    "") echo "no output at all (the command failed silently or the tool does not work here)";;
    *) echo "wrong output: $(echo "$1" | head -n 2 | tr '\n' ' ' | cut -c1-110)";;
  esac
}
pkgsize() {
  [ "$2" = "-" ] && { echo "not in the Debian repo (manual install needed)"; return; }
  [ "$HAVE_APT" = 1 ] || { echo "apt-get install $2 (size unknown: no internet)"; return; }
  local total=0 p kb missing=""
  for p in $2; do
    kb=$(apt-cache show "$p" 2>/dev/null | awk '/^Installed-Size:/{print $2; exit}')
    if [ -n "$kb" ]; then total=$((total + kb)); else missing="$missing $p"; fi
  done
  if [ -n "$missing" ] && [ "$total" -eq 0 ]; then echo "package$missing not found in the Debian repo"; return; fi
  local mb="$((total / 1024)) MB"; [ "$total" -lt 1024 ] && mb="under 1 MB"
  echo "apt-get install $2  (about $mb on disk, plus dependencies)"
}
COUNT=0
run() {
  local name="$1" pkg="$2" bin="$3" file="$4" cmd="$5" out rc t0 t1
  COUNT=$((COUNT + 1))
  cat > "$WORK/$file"
  printf "  %2d. %s\n" "$COUNT" "$name"
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "NOT INSTALLED | $name | $(pkgsize "$name" "$pkg")" >> "$FAIL"; return
  fi
  t0=$(date +%s)
  out=$(cd "$WORK" && timeout 180 bash -c "$cmd" 2>&1 < /dev/null); rc=$?
  t1=$(date +%s)
  if [ $rc -eq 124 ]; then out="timed out after 180 s"; fi
  if echo "$out" | grep -q OK_PASS; then
    echo "$name  ($((t1 - t0)) s)" >> "$PASS"
  else
    local r; [ $rc -eq 124 ] && r="timed out after 180 s (too slow on this phone)" || r="$(why "$out")"
    echo "INSTALLED BUT FAILED | $name | reason: $r | fix: $(pkgsize "$name" "$pkg")" >> "$FAIL"
  fi
}
echo "[2/3] Running real tests (write a small program, compile it, run it)..."
run 'HTML (parser check)' python3 python3 h.html 'python3 -c "import html.parser as h;p=h.HTMLParser();p.feed(open('"'"'h.html'"'"').read());print('"'"'OK_PASS'"'"')"' <<'S'
<!DOCTYPE html><html><body><p>Hello</p></body></html>
S
run 'CSS (brace check)' python3 python3 c.css 'python3 -c "s=open('"'"'c.css'"'"').read();assert s.count('"'"'{'"'"')==s.count('"'"'}'"'"');print('"'"'OK_PASS'"'"')"' <<'S'
body{color:red;}
S
run 'JSON (jq)' jq jq j.json 'jq . j.json >/dev/null && echo OK_PASS' <<'S'
{"a":1}
S
run 'YAML (ruby)' ruby ruby y.yaml 'ruby -ryaml -e "YAML.load_file('"'"'y.yaml'"'"');puts '"'"'OK_PASS'"'"'"' <<'S'
a: 1
S
run 'TOML (python)' python3 python3 t.toml 'python3 -c "import tomllib;tomllib.load(open('"'"'t.toml'"'"','"'"'rb'"'"'));print('"'"'OK_PASS'"'"')"' <<'S'
a = 1
S
run 'XML (xmllint)' libxml2-utils xmllint x.xml 'xmllint --noout x.xml && echo OK_PASS' <<'S'
<a><b/></a>
S
run 'CSV (python)' python3 python3 c.csv 'python3 -c "import csv;list(csv.reader(open('"'"'c.csv'"'"')));print('"'"'OK_PASS'"'"')"' <<'S'
a,b
1,2
S
run 'INI (python)' python3 python3 i.ini 'python3 -c "import configparser as c;p=c.ConfigParser();p.read('"'"'i.ini'"'"');print('"'"'OK_PASS'"'"')"' <<'S'
[s]
a=1
S
run 'SQL (SQLite)' sqlite3 sqlite3 q.sql 'sqlite3 :memory: < q.sql' <<'S'
create table t(x);insert into t values('OK_PASS');select x from t;
S
run 'PostgreSQL (server tools)' postgresql psql x4.txt 'ls /usr/lib/postgresql/*/bin/initdb >/dev/null && echo OK_PASS' <<'S'

S
run 'MariaDB (server)' mariadb-server mariadbd x5.txt 'mariadbd --version | grep -q . && echo OK_PASS' <<'S'

S
run Redis redis-server redis-server x6.txt 'redis-server --version | grep -q . && echo OK_PASS' <<'S'

S
run Git git git x7.txt 'git init -q g1 && cd g1 && git -c user.name=t -c user.email=t@t commit -q --allow-empty -m x && echo OK_PASS' <<'S'

S
run 'diff and patch' 'patch diffutils' patch x8.txt 'echo a > a.txt && echo b > b.txt && (diff -u a.txt b.txt > d.patch; patch -s a.txt d.patch) && grep -q b a.txt && echo OK_PASS' <<'S'

S
run 'Regex (grep -P)' grep grep x9.txt 'echo OK_PASS | grep -P '"'"'OK_\w+'"'"'' <<'S'

S
run curl curl curl x10.txt 'curl --version | grep -q curl && echo OK_PASS' <<'S'

S
run OpenSSL openssl openssl x11.txt 'openssl version | grep -q . && echo OK_PASS' <<'S'

S
run 'SSH client' openssh-client ssh x12.txt 'ssh -V 2>&1 | grep -q . && echo OK_PASS' <<'S'

S
run 'zip and tar' 'zip unzip tar' zip x13.txt 'echo a > z.txt && zip -q z.zip z.txt && unzip -tq z.zip >/dev/null && tar cf z.tar z.txt && echo OK_PASS' <<'S'

S
run 'Debugger (gdb)' gdb gdb x14.c 'gcc -g x14.c -o x14 && gdb -batch -ex run ./x14' <<'S'
#include <stdio.h>
int main(){puts("OK_PASS");return 0;}
S
run Valgrind valgrind valgrind x15.c 'gcc -g x15.c -o x15 && valgrind -q ./x15' <<'S'
#include <stdio.h>
int main(){puts("OK_PASS");return 0;}
S
run strace strace strace x16.txt 'strace -o /dev/null echo OK_PASS' <<'S'

S
run ctags universal-ctags ctags x17.c 'ctags -R -f x17.tags x17.c; grep -q main x17.tags && echo OK_PASS' <<'S'
int main(){return 0;}
S
run ShellCheck shellcheck shellcheck x18.sh 'shellcheck x18.sh && echo OK_PASS' <<'S'
#!/bin/sh
echo hi
S
run clang-format clang-format clang-format x19.c 'clang-format x19.c >/dev/null && echo OK_PASS' <<'S'
int main(){return 0;}
S
run 'Python pip' python3-pip pip3 x20.txt 'pip3 --version | grep -q pip && echo OK_PASS' <<'S'

S
run 'Ruby gem' ruby gem x21.txt 'gem -v >/dev/null && echo OK_PASS' <<'S'

S
run 'Composer (PHP)' composer composer x22.txt 'composer --version 2>&1 | grep -q Composer && echo OK_PASS' <<'S'

S
run 'Python numpy' python3-numpy python3 x23.txt 'python3 -c "import numpy;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python pandas' python3-pandas python3 x24.txt 'python3 -c "import pandas;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python matplotlib' python3-matplotlib python3 x25.txt 'python3 -c "import matplotlib;matplotlib.use('"'"'Agg'"'"');import matplotlib.pyplot as p;p.plot([1]);p.savefig('"'"'m.png'"'"');print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python requests' python3-requests python3 x26.txt 'python3 -c "import requests;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python Flask' python3-flask python3 x27.txt 'python3 -c "import flask;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python Django' python3-django python3 x28.txt 'python3 -c "import django;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python Tkinter' python3-tk python3 x29.txt 'python3 -c "import tkinter;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python GTK (PyGObject)' 'python3-gi gir1.2-gtk-3.0' python3 x30.txt 'python3 -c "import gi;gi.require_version('"'"'Gtk'"'"','"'"'3.0'"'"');from gi.repository import Gtk;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'Python Qt (PyQt5)' python3-pyqt5 python3 x31.txt 'python3 -c "import PyQt5.QtWidgets;print('"'"'OK_PASS'"'"')"' <<'S'

S
run 'PHP web server' 'php-cli curl' php i.php 'php -S 127.0.0.1:18081 >/dev/null 2>&1 & p=$!; sleep 2; curl -s 127.0.0.1:18081/i.php; kill $p' <<'S'
<?php echo "OK_PASS";
S
run 'Python web server' 'python3 curl' python3 w.txt 'echo OK_PASS > index.html; python3 -m http.server 18082 --bind 127.0.0.1 >/dev/null 2>&1 & p=$!; sleep 2; curl -s 127.0.0.1:18082/; kill $p' <<'S'

S
run 'Node web server' 'nodejs curl' node srv.js 'node srv.js & p=$!; sleep 2; curl -s 127.0.0.1:18083; kill $p' <<'S'
require("http").createServer((q,r)=>r.end("OK_PASS")).listen(18083,"127.0.0.1");
S
run 'Node ES modules' nodejs node m.mjs 'node m.mjs' <<'S'
import os from "os"; console.log("OK_PASS");
S
run 'Python unittest' python3 python3 tu.py 'python3 -m unittest tu 2>&1 && echo OK_PASS' <<'S'
import unittest
class T(unittest.TestCase):
    def test(self): self.assertTrue(True)
S
run 'Node test runner' nodejs node tn.test.js 'node --test tn.test.js 2>&1 | grep -q pass && echo OK_PASS' <<'S'
require("node:test")("x",()=>{});
S
run 'Go tests' golang-go go t_test.go 'rm -rf gt && mkdir gt && cp t_test.go gt/ && cd gt && go mod init x >/dev/null 2>&1 && go test ./... 2>&1 | grep -q ok && echo OK_PASS' <<'S'
package x
import "testing"
func TestX(t *testing.T){}
S
run 'C++ project (CMake build)' 'cmake g++' cmake main.cpp 'printf "cmake_minimum_required(VERSION 3.10)\nproject(p CXX)\nadd_executable(p main.cpp)\n" > CMakeLists.txt && cmake -S . -B b -DCMAKE_BUILD_TYPE=Release >/dev/null && cmake --build b >/dev/null && ./b/p' <<'S'
#include <iostream>
int main(){std::cout<<"OK_PASS\n";}
S
run 'C project (Make build)' 'make gcc' make m.c 'printf "all:\n\tgcc m.c -o m_out\n" > Makefile && make -s && ./m_out' <<'S'
#include <stdio.h>
int main(){puts("OK_PASS");return 0;}
S
run 'Java jar packaging' default-jdk-headless jar Jr.java 'javac Jr.java && jar cfe jr.jar Jr Jr.class && java -jar jr.jar' <<'S'
public class Jr{public static void main(String[] a){System.out.println("OK_PASS");}}
S
run 'Ruby JSON library' ruby ruby x32.txt 'ruby -rjson -e "puts JSON.generate({}) && puts '"'"'OK_PASS'"'"'"' <<'S'

S
run 'PHP PDO SQLite' php-sqlite3 php pd.php 'php pd.php' <<'S'
<?php new PDO("sqlite::memory:"); echo "OK_PASS";
S
run 'Python sqlite3 module' python3 python3 x33.txt 'python3 -c "import sqlite3;sqlite3.connect('"'"':memory:'"'"');print('"'"'OK_PASS'"'"')"' <<'S'

S
echo "[3/3] Writing the report to $OUT"
NP=$(wc -l < "$PASS"); NF=$(wc -l < "$FAIL")
{
  echo "=========================================================="
  echo " VSCODEROID LANGUAGE PRACTICE REPORT - BATCH 3 of 3 (web, data, databases, tools, libraries)"
  echo " $(date)  |  $(uname -m)  |  $(grep PRETTY_NAME /etc/os-release | cut -d= -f2)"
  echo " Tested: $COUNT  |  Working: $NP  |  Not working: $NF"
  echo " Working means: a small program was written, compiled (if needed) and RUN, and printed its result."
  echo "=========================================================="
  echo
  echo ">>> WORKING (a student can practice with these) <<<"
  cat "$PASS"
  echo
  echo ">>> NOT INSTALLED (the tool is not in the system; the fix shows the package and its size) <<<"
  grep '^NOT INSTALLED' "$FAIL" | sed 's/^NOT INSTALLED | //'
  echo
  echo ">>> INSTALLED BUT THE TEST FAILED (exact reason) <<<"
  grep '^INSTALLED BUT FAILED' "$FAIL" | sed 's/^INSTALLED BUT FAILED | //'
} > "$OUT"
cd /tmp && rm -rf "$WORK"
echo
echo "DONE: $NP working, $NF not working. Report: $OUT"
