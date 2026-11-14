#!/bin/sh
# Re-enable every package this project disabled (backup/disabled-by-us.txt),
# hand Home back to the stock Google TV launcher and turn Private DNS back to default.
cd "$(dirname "$0")"
TV=tools/tv
for p in $(cat backup/disabled-by-us.txt); do
  echo "$p: $($TV "pm enable --user 0 $p")"
done
$TV 'cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity'
$TV 'settings put global private_dns_mode opportunistic'
$TV 'settings delete global private_dns_specifier'
echo "Done. Reboot the TV (tools/tv reboot) to finish."
