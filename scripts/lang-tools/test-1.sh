#!/bin/bash
export DEBIAN_FRONTEND=noninteractive
export HOME="${HOME:-/root}"
OUTDIR="${LT_OUTDIR:-/storage/emulated/0/.vscoderoid-pro}"
mkdir -p "$OUTDIR" 2>/dev/null
OUT="$OUTDIR/language_test_1.txt"
WORK="$(mktemp -d /tmp/lt1.XXXXXX)"
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
run 'C (gcc)' gcc gcc t.c 'gcc t.c -o t_c && ./t_c' <<'S'
#include <stdio.h>
int main(){puts("OK_PASS");return 0;}
S
run 'C++ (g++)' g++ g++ t.cpp 'g++ t.cpp -o t_cpp && ./t_cpp' <<'S'
#include <iostream>
int main(){std::cout<<"OK_PASS\n";}
S
run 'C (clang)' clang clang c2.c 'clang c2.c -o t_cl && ./t_cl' <<'S'
#include <stdio.h>
int main(){puts("OK_PASS");return 0;}
S
run 'C++ (clang++)' clang clang++ c2.cpp 'clang++ c2.cpp -o t_clpp && ./t_clpp' <<'S'
#include <iostream>
int main(){std::cout<<"OK_PASS\n";}
S
run 'Objective-C (gobjc)' gobjc gcc o.m 'gcc -x objective-c o.m -lobjc -o t_oc && ./t_oc' <<'S'
#include <objc/Object.h>
#include <stdio.h>
@interface Hi : Object
-(void)say;
@end
@implementation Hi
-(void)say{ puts("OK_PASS"); }
@end
int main(){ id o=[Hi new]; [o say]; return 0; }
S
run 'Assembly (GNU as, arm64)' 'binutils gcc' gcc a.S '[ "$(uname -m)" = aarch64 ] && gcc a.S -o t_asm && ./t_asm' <<'S'
.text
.global main
main:
  stp x29, x30, [sp, -16]!
  adr x0, msg
  bl puts
  mov w0, 0
  ldp x29, x30, [sp], 16
  ret
msg: .asciz "OK_PASS"
S
run 'Rust (rustc)' rustc rustc t.rs 'rustc t.rs -o t_rs && ./t_rs' <<'S'
fn main(){println!("OK_PASS");}
S
run 'Rust project (cargo)' cargo cargo m.rs 'rm -rf cp && cargo new --quiet cp && cp m.rs cp/src/main.rs && cd cp && cargo run -q --offline' <<'S'
fn main(){println!("OK_PASS");}
S
run Go golang-go go t.go 'go run t.go' <<'S'
package main
import "fmt"
func main(){fmt.Println("OK_PASS")}
S
run 'Java (javac + java)' default-jdk-headless javac Main.java 'javac Main.java && java Main' <<'S'
public class Main{public static void main(String[] a){System.out.println("OK_PASS");}}
S
run 'Java (single file run)' default-jdk-headless java J2.java 'java J2.java' <<'S'
public class J2{public static void main(String[] a){System.out.println("OK_PASS");}}
S
run Kotlin kotlin kotlinc k.kt 'kotlinc k.kt -include-runtime -d k.jar 2>/dev/null && java -jar k.jar' <<'S'
fun main(){println("OK_PASS")}
S
run Scala scala scalac S.scala 'scalac S.scala && scala S' <<'S'
object S extends App{println("OK_PASS")}
S
run Groovy groovy groovy g.groovy 'groovy g.groovy' <<'S'
println "OK_PASS"
S
run Clojure clojure clojure c.clj 'clojure c.clj' <<'S'
(println "OK_PASS")
S
run 'JavaScript (Node.js)' nodejs node n.js 'node n.js' <<'S'
console.log("OK_PASS")
S
run 'TypeScript (tsc)' node-typescript tsc ts.ts 'tsc ts.ts && node ts.js' <<'S'
const s: string = "OK_PASS"; console.log(s);
S
run 'Python 3' python3 python3 p.py 'python3 p.py' <<'S'
print("OK_PASS")
S
run 'Python venv' python3-venv python3 x0.txt 'python3 -m venv v && v/bin/python -c '"'"'print("OK_PASS")'"'"'' <<'S'

S
run Ruby ruby ruby r.rb 'ruby r.rb' <<'S'
puts "OK_PASS"
S
run PHP php-cli php p.php 'php p.php' <<'S'
<?php echo "OK_PASS\n";
S
run Perl perl perl p.pl 'perl p.pl' <<'S'
print "OK_PASS\n";
S
run 'Lua 5.4' lua5.4 lua5.4 l.lua 'lua5.4 l.lua' <<'S'
print("OK_PASS")
S
run Bash bash bash b.sh 'bash b.sh' <<'S'
echo OK_PASS
S
run 'Dash (sh)' dash dash d.sh 'dash d.sh' <<'S'
echo OK_PASS
S
run Zsh zsh zsh z.zsh 'zsh z.zsh' <<'S'
echo OK_PASS
S
run Fish fish fish f.fish 'fish f.fish' <<'S'
echo OK_PASS
S
run Ksh ksh ksh k.ksh 'ksh k.ksh' <<'S'
echo OK_PASS
S
run 'Fortran (gfortran)' gfortran gfortran f.f90 'gfortran f.f90 -o t_f && ./t_f' <<'S'
program t
print *, "OK_PASS"
end program t
S
run 'Pascal (fpc)' fp-compiler fpc p.pas 'fpc -v0 p.pas && ./p' <<'S'
program P;
begin
writeln('OK_PASS');
end.
S
run 'COBOL (gnucobol)' gnucobol cobc c.cob 'cobc -x -free c.cob -o t_cob && ./t_cob' <<'S'
IDENTIFICATION DIVISION.
PROGRAM-ID. T.
PROCEDURE DIVISION.
DISPLAY "OK_PASS".
STOP RUN.
S
run 'Ada (gnat)' gnat gnatmake hi.adb 'gnatmake -q hi.adb && ./hi' <<'S'
with Ada.Text_IO;
procedure Hi is
begin
Ada.Text_IO.Put_Line("OK_PASS");
end Hi;
S
run 'D (ldc)' ldc ldc2 d.d 'ldc2 d.d -of=t_d && ./t_d' <<'S'
import std.stdio;
void main(){writeln("OK_PASS");}
S
run Nim nim nim n.nim 'nim c -r --hints:off --verbosity:0 n.nim' <<'S'
echo "OK_PASS"
S
run Zig - zig z.zig 'zig run z.zig' <<'S'
const std = @import("std");
pub fn main() void { std.debug.print("OK_PASS\n", .{}); }
S
run Swift - swiftc s.swift 'swiftc s.swift -o t_sw && ./t_sw' <<'S'
print("OK_PASS")
S
run Dart - dart d.dart 'dart d.dart' <<'S'
void main(){print("OK_PASS");}
S
run 'C# (Mono)' 'mono-mcs mono-runtime' mcs c.cs 'mcs c.cs -out:c.exe && mono c.exe' <<'S'
class P{static void Main(){System.Console.WriteLine("OK_PASS");}}
S
run 'C# (.NET SDK present)' - dotnet x.txt 'dotnet --list-sdks | grep -q . && echo OK_PASS' <<'S'

S
run 'F# (fsharp)' fsharp fsharpc f.fs 'fsharpc f.fs --nologo -o:f.exe && mono f.exe' <<'S'
printfn "OK_PASS"
S
run Vala valac valac v.vala 'valac v.vala -o t_vala && ./t_vala' <<'S'
void main(){print("OK_PASS\n");}
S
run 'Haskell (ghc)' ghc runghc h.hs 'runghc h.hs' <<'S'
main = putStrLn "OK_PASS"
S
run OCaml ocaml ocaml o.ml 'ocaml o.ml' <<'S'
print_endline "OK_PASS"
S
run Erlang erlang escript e.escript 'escript e.escript' <<'S'
#!/usr/bin/env escript
main(_) -> io:format("OK_PASS~n").
S
run Elixir elixir elixir ex.exs 'elixir ex.exs' <<'S'
IO.puts("OK_PASS")
S
run R r-base-core Rscript r.R 'Rscript r.R' <<'S'
cat("OK_PASS\n")
S
run Julia julia julia j.jl 'julia j.jl' <<'S'
println("OK_PASS")
S
run Octave octave octave o.m 'octave --no-gui --quiet o.m' <<'S'
disp("OK_PASS")
S
run Tcl tcl tclsh t.tcl 'tclsh t.tcl' <<'S'
puts "OK_PASS"
S
run 'AWK (gawk)' gawk gawk a.awk 'gawk -f a.awk /dev/null' <<'S'
BEGIN{print "OK_PASS"}
S
echo "[3/3] Writing the report to $OUT"
NP=$(wc -l < "$PASS"); NF=$(wc -l < "$FAIL")
{
  echo "=========================================================="
  echo " VSCODEROID LANGUAGE PRACTICE REPORT - BATCH 1 of 3 (popular and system languages)"
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
