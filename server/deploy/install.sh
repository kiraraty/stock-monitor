#!/bin/sh
set -eu
# Run on the target as root from the extracted, tested release directory.
RELEASE=${1:?release name required}
case "$RELEASE" in *[!a-zA-Z0-9_-]*|'') exit 2;; esac
getent passwd pnale >/dev/null || useradd --system --home-dir /var/lib/pnale-monitor --shell /usr/sbin/nologin pnale
install -d -m 0755 /opt/pnale-monitor/releases
install -d -m 0750 -o root -g pnale /etc/pnale-monitor
install -d -m 0700 -o pnale -g pnale /var/lib/pnale-monitor
install -d -m 0755 "/opt/pnale-monitor/releases/$RELEASE"
install -m 0755 pnale-monitor "/opt/pnale-monitor/releases/$RELEASE/pnale-monitor"
install -m 0644 moomoo_bridge.py "/opt/pnale-monitor/releases/$RELEASE/moomoo_bridge.py"
if [ -f /etc/pnale-monitor/config.json ]; then cp -p /etc/pnale-monitor/config.json "/etc/pnale-monitor/config.before-$RELEASE.json"; fi
install -m 0640 -o root -g pnale config.json /etc/pnale-monitor/config.json
if [ -L /opt/pnale-monitor/current ]; then readlink /opt/pnale-monitor/current > "/opt/pnale-monitor/releases/$RELEASE/previous-path"; fi
ln -sfn "/opt/pnale-monitor/releases/$RELEASE" /opt/pnale-monitor/current
install -m 0644 pnale-monitor.service /etc/systemd/system/pnale-monitor.service
systemctl daemon-reload
systemctl enable pnale-monitor.service
systemctl restart pnale-monitor.service
