#!/bin/sh
# Disable every package listed in the given list files (one per line, # comments),
# one `pm` call per package, and log what was disabled to backup/disabled-by-us.txt.
# Refuses any list containing Android core packages the TV needs to boot.
set -u
cd "$(dirname "$0")/.."
TV=tools/tv
CORE='^(android|com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings|com\.android\.tv\.settings|(com\.android|com\.google\.android)\.(permissioncontroller|packageinstaller|ext\.services|ext\.shared|modulemetadata)|com\.google\.android\.overlay\.modules.*|com\.android\.providers\.(.*)|com\.android\.vending|com\.google\.android\.gms|com\.google\.android\.gsf|com\.google\.android\.inputmethod\.latin|com\.tcl\.tv|com\.tcl\.tvinput|com\.tcl\.tcl_bt_rcu_service|com\.android\.bluetooth|com\.android\.inputdevices)$'
OK_PROVIDERS='^com\.android\.providers\.(calendar|contacts|userdictionary|blockednumber)$'
pkgs=$(cat "$@" | sed 's/#.*//' | tr -d ' \r' | grep -v '^$')
for p in $pkgs; do
  if echo "$p" | grep -Eq "$CORE" && ! echo "$p" | grep -Eq "$OK_PROVIDERS"; then
    echo "REFUSING: list contains core package $p" >&2; exit 2
  fi
  case "$p" in *fac*|*factory*|*.at.*) echo "REFUSING factory package $p" >&2; exit 2;; esac
done
# Never leave the TV without a Home: some HOME activity other than Settings'
# FallbackHome must remain outside the list.
homes=$($TV 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
  | grep '/' | sed 's/^ *//' | cut -d/ -f1 | grep -vx com.android.tv.settings)
if [ -z "$homes" ]; then
  echo "WARNING: could not list Home apps; nothing was checked" >&2
else
  left=$(echo "$homes" | while read -r h; do echo "$pkgs" | grep -qx "$h" || echo "$h"; done)
  [ -z "$left" ] && { echo "REFUSING: this would disable every Home app ($homes)" >&2; exit 2; }
fi
mkdir -p backup
for p in $pkgs; do
  out=$($TV "pm disable-user --user 0 $p" 2>&1)
  echo "$p: $out"
  if $TV 'pm list packages -d' | grep -qx "package:$p"; then
    grep -qx "$p" backup/disabled-by-us.txt 2>/dev/null || echo "$p" >> backup/disabled-by-us.txt
  else
    echo "  !! $p is NOT in the disabled list" >&2
  fi
done
