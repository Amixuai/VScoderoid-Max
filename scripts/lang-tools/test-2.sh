#!/bin/bash
export DEBIAN_FRONTEND=noninteractive
export HOME="${HOME:-/root}"
OUTDIR="${LT_OUTDIR:-/storage/emulated/0/.vscoderoid-pro}"
mkdir -p "$OUTDIR" 2>/dev/null
OUT="$OUTDIR/language_test_2.txt"
WORK="$(mktemp -d /tmp/lt2.XXXXXX)"
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
    *"Permission denied"*) echo "permission problem";;
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
run Racket racket racket r.rkt 'racket r.rkt' <<'S'
#lang racket
(displayln "OK_PASS")
S
run 'Scheme (guile)' guile-3.0 guile g.scm 'guile g.scm' <<'S'
(display "OK_PASS")(newline)
S
run 'Scheme (chicken)' chicken-bin csi c.scm 'csi -script c.scm' <<'S'
(print "OK_PASS")
S
run 'Scheme (gauche)' gauche gosh g2.scm 'gosh g2.scm' <<'S'
(print "OK_PASS")
S
run 'Scheme (MIT)' mit-scheme mit-scheme m.scm 'mit-scheme --quiet < m.scm' <<'S'
(display "OK_PASS")(newline)(exit)
S
run 'Common Lisp (sbcl)' sbcl sbcl l.lisp 'sbcl --script l.lisp' <<'S'
(format t "OK_PASS~%")
S
run 'Common Lisp (ecl)' ecl ecl e.lisp 'ecl --shell e.lisp' <<'S'
(format t "OK_PASS~%")
S
run 'Common Lisp (clisp)' clisp clisp c.lisp 'clisp c.lisp' <<'S'
(format t "OK_PASS~%")
S
run 'Emacs Lisp' emacs-nox emacs e.el 'emacs --batch --script e.el' <<'S'
(message "OK_PASS")
S
run 'Prolog (SWI)' swi-prolog swipl p.pl 'swipl -q p.pl' <<'S'
:- initialization(main).
main :- write('OK_PASS'), nl, halt.
S
run 'Prolog (GNU)' gprolog gplc g.pl 'gplc g.pl -o t_gp && ./t_gp' <<'S'
:- initialization(main).
main :- write('OK_PASS'), nl, halt.
S
run 'Forth (gforth)' gforth gforth f.fs 'gforth f.fs -e bye' <<'S'
.( OK_PASS) cr
S
run 'Smalltalk (gst)' gnu-smalltalk gst s.st 'gst s.st' <<'S'
Transcript showCr: 'OK_PASS'.
S
run Haxe haxe haxe Main.hx 'haxe -main Main --interp' <<'S'
class Main{static function main(){trace("OK_PASS");}}
S
run Crystal - crystal c.cr 'crystal run c.cr' <<'S'
puts "OK_PASS"
S
run Raku rakudo raku r.raku 'raku r.raku' <<'S'
say "OK_PASS"
S
run 'Lua (LuaJIT)' luajit luajit lj.lua 'luajit lj.lua' <<'S'
print("OK_PASS")
S
run 'AWK (mawk)' mawk mawk m.awk 'mawk -f m.awk /dev/null' <<'S'
BEGIN{print "OK_PASS"}
S
run Tcsh tcsh tcsh t.csh 'tcsh t.csh' <<'S'
echo OK_PASS
S
run Csh csh csh c.csh 'csh c.csh' <<'S'
echo OK_PASS
S
run 'Busybox ash' busybox busybox a.sh 'busybox sh a.sh' <<'S'
echo OK_PASS
S
run PowerShell - pwsh p.ps1 'pwsh -NoProfile -File p.ps1' <<'S'
Write-Output "OK_PASS"
S
run 'Verilog (iverilog)' iverilog iverilog t.v 'iverilog -o t_v t.v && vvp t_v' <<'S'
module t;
initial begin $display("OK_PASS"); $finish; end
endmodule
S
run 'VHDL (ghdl)' ghdl ghdl t.vhd 'ghdl -a t.vhd && ghdl -e t && ghdl -r t --stop-time=1ns' <<'S'
entity t is end t;
architecture a of t is
begin
process begin report "OK_PASS"; wait; end process;
end a;
S
run Maxima maxima maxima m.mac 'maxima -q -b m.mac' <<'S'
print("OK_PASS");
S
run GAP gap gap g.g 'gap -q g.g' <<'S'
Print("OK_PASS\n");
QUIT;
S
run PARI/GP pari-gp gp g.gp 'gp -q g.gp' <<'S'
print("OK_PASS");quit
S
run Gnuplot gnuplot-nox gnuplot g.plt 'gnuplot g.plt' <<'S'
print "OK_PASS"
S
run bc bc bc b.bc 'bc -q b.bc' <<'S'
print "OK_PASS\n"
quit
S
run dc dc dc d.dc 'dc -f d.dc' <<'S'
[OK_PASS]p
S
run 'Graphviz (dot)' graphviz dot g.dot 'dot -Tsvg g.dot -o g.svg && echo OK_PASS' <<'S'
digraph{a->b}
S
run 'LaTeX (pdflatex)' texlive-latex-base pdflatex l.tex 'pdflatex -interaction=nonstopmode l.tex >/dev/null && echo OK_PASS' <<'S'
\documentclass{article}\begin{document}Hello\end{document}
S
run 'Markdown (pandoc)' pandoc pandoc m.md 'pandoc m.md -o m.html && echo OK_PASS' <<'S'
# Hello
S
run 'Vim script' vim vim v.vim 'vim -es -u NONE -S v.vim; cat vimout.txt' <<'S'
call writefile(["OK_PASS"], "vimout.txt")
qa!
S
run 'jq language' jq jq j.jq 'jq -n -r -f j.jq' <<'S'
"OK_PASS"
S
run sed sed sed s.sed 'echo x | sed -f s.sed' <<'S'
s/x/OK_PASS/
S
run Make make make x40.txt 'printf "all:\n\t@echo OK_PASS\n" > Makefile && make -s' <<'S'

S
run 'CMake script' cmake cmake c.cmake 'cmake -P c.cmake' <<'S'
message(STATUS "OK_PASS")
S
run 'Maven present' maven mvn x.txt 'mvn -v | grep -q Maven && echo OK_PASS' <<'S'

S
run 'Gradle present' gradle gradle x2.txt 'gradle -v | grep -q Gradle && echo OK_PASS' <<'S'

S
run Scilab scilab-cli scilab-cli s.sce 'scilab-cli -nb -f s.sce' <<'S'
disp("OK_PASS");exit
S
run Coq coq coqc c.v 'coqc c.v' <<'S'
Require Import String.
Open Scope string_scope.
Eval compute in "OK_PASS".
S
run 'Standard ML (smlnj)' smlnj sml s.sml 'sml s.sml < /dev/null' <<'S'
print "OK_PASS\n";
S
run 'Rexx (regina)' regina-rexx regina r.rexx 'regina r.rexx' <<'S'
say "OK_PASS"
S
run 'Java (jshell)' default-jdk-headless jshell j.jsh 'jshell j.jsh' <<'S'
System.out.println("OK_PASS")
/exit
S
run 'Python (PyPy)' pypy3 pypy3 pp.py 'pypy3 pp.py' <<'S'
print("OK_PASS")
S
run 'Node npm project' npm npm x3.txt 'npm init -y >/dev/null 2>&1 && echo OK_PASS' <<'S'

S
run CoffeeScript node-coffeescript coffee c.coffee 'coffee c.coffee' <<'S'
console.log "OK_PASS"
S
run Janet janet janet j.janet 'janet j.janet' <<'S'
(print "OK_PASS")
S
run 'LLVM IR (lli)' llvm lli l.ll 'lli l.ll' <<'S'
@s = private constant [8 x i8] c"OK_PASS\00"
declare i32 @puts(ptr)
define i32 @main() {
  call i32 @puts(ptr @s)
  ret i32 0
}
S
echo "[3/3] Writing the report to $OUT"
NP=$(wc -l < "$PASS"); NF=$(wc -l < "$FAIL")
{
  echo "=========================================================="
  echo " VSCODEROID LANGUAGE PRACTICE REPORT - BATCH 2 of 3 (functional, scientific, scripting, hardware)"
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
