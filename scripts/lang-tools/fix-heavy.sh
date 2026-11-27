#!/bin/bash
# VSCodroid language fixer (HEAVY SET (OCaml, Kotlin, Coq, Pandoc, Maxima, Racket, GHC)). Installs missing language tools from the Debian repo and
# writes what happened, per package, to a report in the public folder. UNTESTED on a phone.
export DEBIAN_FRONTEND=noninteractive
OUTDIR="${LT_OUTDIR:-/storage/emulated/0/.vscoderoid-pro}"
mkdir -p "$OUTDIR" 2>/dev/null
OUT="$OUTDIR/fix_report_heavy.txt"
LOG="$(mktemp /tmp/fix.XXXXXX)"
# Results are kept between runs, so if Android ends the desktop half way, running this again
# carries on (what is installed is skipped) and nothing already done is lost.
OK="$OUTDIR/.fix_heavy_done"; BAD="$LOG.bad"; touch "$OK"; : > "$BAD"
APT="nice -n 19 apt-get -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef"
echo "[1/4] Repairing dpkg and refreshing the package list..."
if [ -n "$(dpkg --audit 2>/dev/null)" ]; then echo "  (finishing an interrupted install first, this can take a few minutes)"; dpkg --configure -a >/dev/null 2>&1; fi
# No service may start while installing (there is no systemd inside this system).
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d && chmod +x /usr/sbin/policy-rc.d
if ! apt-get update -qq >/dev/null 2>&1; then
  echo "No internet or the Debian mirror is not reachable: nothing can be installed. Try again with a connection."
  rm -f /usr/sbin/policy-rc.d "$LOG" "$BAD"; exit 1
fi
USED_BEFORE=$(du -xsm /usr 2>/dev/null | cut -f1)
FREE_MB=$(df -Pm /tmp 2>/dev/null | awk 'NR==2{print $4}')
echo "If the screen turns to Not connected while this runs, Android ended the desktop: open it again, run this command again (it carries on), and send desktop_exit_reasons.txt."
echo "[2/4] Installing (about 7 packages, one by one; each line shows the result)..."
fix() {
  local pkg="$1" cand sim out rc reason
  if dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q "install ok installed"; then
    echo "INSTALLED | $pkg | already there" >> "$OK"; printf "  == %-26s already installed\n" "$pkg"; return
  fi
  cand=$(apt-cache policy "$pkg" 2>/dev/null | awk '/Candidate:/{print $2}')
  if [ -z "$cand" ] || [ "$cand" = "(none)" ]; then
    echo "NOT IN REPO | $pkg | not available in this Debian 13 arm64 repo (removed, or never built for arm64): needs a manual install or another tool" >> "$BAD"
    printf "  -- %-26s not in repo\n" "$pkg"; return
  fi
  sim=$($APT -s install --no-install-recommends "$pkg" 2>/dev/null | awk '/After this operation/{print $4 " " $5}')
  out=$($APT install -y --no-install-recommends "$pkg" 2>&1); rc=$?
  if [ $rc -ne 0 ] && echo "$out" | grep -q "dpkg was interrupted"; then
    dpkg --configure -a >/dev/null 2>&1
    out=$($APT install -y --no-install-recommends "$pkg" 2>&1); rc=$?
  fi
  if [ $rc -eq 0 ]; then
    echo "INSTALLED | $pkg | adds about ${sim:-unknown size}" >> "$OK"
    printf "  ok %-26s +%s\n" "$pkg" "${sim:-?}"
  else
    case "$out" in
      *"No space left"*) reason="the phone ran out of storage";;
      *"Unable to locate"*) reason="package name not found";;
      *"unmet dependencies"*|*"Depends:"*) reason="a dependency is missing or conflicts: $(echo "$out" | grep -E 'Depends:|Unable' | head -n 1 | cut -c1-110)";;
      *"Temporary failure"*|*"Could not resolve"*|*"Failed to fetch"*|*"Connection"*) reason="download failed (network)";;
      *"dpkg: error"*|*"post-installation"*|*"subprocess"*) reason="the package unpacked but its setup script failed (PRoot limit): $(echo "$out" | grep -E 'dpkg: error|E:' | tail -n 1 | cut -c1-110)";;
      *) reason="$(echo "$out" | grep -E '^E:' | tail -n 1 | cut -c1-130)";;
    esac
    [ -z "$reason" ] && reason="unknown error (see: apt-get install $pkg)"
    echo "FAILED | $pkg | $reason" >> "$BAD"
    printf "  !! %-26s failed\n" "$pkg"
  fi
}
for p in ocaml kotlin coq pandoc maxima racket ghc; do fix "$p"; done
echo "[3/4] Removing weight nobody reads (docs, manual pages, other languages), and caches..."
find /usr/share/doc -mindepth 1 \( -type f -o -type l \) ! -name copyright -delete 2>/dev/null
find /usr/share/doc -mindepth 1 -type d -empty -delete 2>/dev/null
rm -rf /usr/share/man/* /usr/share/info/* /usr/share/lintian /usr/share/doc-base /usr/share/gtk-doc
find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name 'en' ! -name 'en_*' ! -name 'locale.alias' -exec rm -rf {} + 2>/dev/null
apt-get clean >/dev/null 2>&1; rm -rf /var/lib/apt/lists/* /usr/sbin/policy-rc.d
USED_AFTER=$(du -xsm /usr 2>/dev/null | cut -f1)
echo "[4/4] Writing the report to $OUT"
{
  echo "=========================================================="
  echo " VSCODROID LANGUAGE FIX REPORT - HEAVY SET (OCaml, Kotlin, Coq, Pandoc, Maxima, Racket, GHC)"
  echo " $(date)  |  $(uname -m)  |  $(grep PRETTY_NAME /etc/os-release | cut -d= -f2)"
  echo " Installed: $(sort -u "$OK" | wc -l)  |  Failed: $(wc -l < "$BAD")  |  /usr before: ${USED_BEFORE} MB, after: ${USED_AFTER} MB (free before: ${FREE_MB} MB)"
  echo " To prove it works, run command_1, command_2 and command_3 again."
  echo "=========================================================="
  echo; echo ">>> INSTALLED <<<"; sort -u "$OK"
  echo; echo ">>> NOT INSTALLED, WITH THE REASON <<<"; cat "$BAD"
} > "$OUT"
NI=$(sort -u "$OK" | wc -l); NB=$(wc -l < "$BAD")
rm -f "$LOG" "$BAD"
echo
echo "DONE: $NI installed, $NB not installed. /usr grew from ${USED_BEFORE} MB to ${USED_AFTER} MB. Report: $OUT"
