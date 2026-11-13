# Windows and macOS command names, mapped to the Linux command with a plain alias.
# Installed as /etc/profile.d/10-compat-aliases.sh inside the Debian system and
# loaded by interactive shells only (/etc/bash.bashrc sources it). Scripts and make
# never read it, so they behave exactly as on a normal Linux machine.
#
# Rules (brief, section 12):
#   - A name is aliased only when it is not already a command, builtin, keyword,
#     function or alias here: `type -t NAME` must print nothing. The single
#     exception is `dir`, below. The test is made
#     at load time, so an alias can never hide a real command.
#   - Only the command name is translated. Windows switches such as /s or /q are
#     not; type Linux options after the name.
#   - The table below is checked in CI by scripts/check-desktop-aliases.py.
#
# FORMAT, one per line: _vsc_alias NAME 'command'   (the checker reads these lines)

case $- in *i*) ;; *) return 0 2>/dev/null || exit 0 ;; esac

_vsc_alias() {
    [ -n "$(type -t "$1" 2>/dev/null)" ] && return 0
    alias "$1=$2"
}

_vsc_alias cls 'clear'
_vsc_alias copy 'cp'
_vsc_alias move 'mv'
_vsc_alias del 'rm'
_vsc_alias erase 'rm'
_vsc_alias md 'mkdir -p'
_vsc_alias rd 'rmdir'
_vsc_alias where 'which'
_vsc_alias findstr 'grep'
_vsc_alias tasklist 'ps aux'
_vsc_alias ipconfig 'ip addr'
_vsc_alias open 'xdg-open'
_vsc_alias pbcopy 'xclip -selection clipboard'
_vsc_alias pbpaste 'xclip -selection clipboard -o'

# The one name that is also a real Debian program. The Master Blueprint (section 1) names
# `dir` as its own example of a Windows command to translate, and `dir` on Debian lists in
# columns without sizes, which is not what someone typing it expects. It is therefore aliased
# on purpose; the program itself stays reachable as `command dir` or `\dir`.
_vsc_force_alias() { alias "$1=$2"; }
_vsc_force_alias dir 'ls -l'

unset -f _vsc_alias _vsc_force_alias
