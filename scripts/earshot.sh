#!/bin/sh
# Runs Earshot as a macOS login service (owner decision D-8, 2026-10-24): it starts at login and
# launchd starts it again if it stops for any reason (the service went down unnoticed on 2026-10-21).
#
#   scripts/earshot.sh install     build if needed, install the launchd agent and start it
#   scripts/earshot.sh uninstall   stop it and remove the agent (back to starting it by hand)
#   scripts/earshot.sh restart     restart the service (and with it a fresh ML process)
#   scripts/earshot.sh stop|start  stop until the next start or login / start again
#   scripts/earshot.sh status      is it loaded, and is it answering
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LABEL=com.earshot.service
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
DOMAIN="gui/$(id -u)"
JAR="$ROOT/service/target/api-0.0.1-SNAPSHOT.jar"

java_home() { /usr/libexec/java_home -v 25; }

write_plist() {
  mkdir -p "$HOME/Library/LaunchAgents" "$ROOT/data/logs"
  cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(java_home)/bin/java</string>
    <string>-jar</string>
    <string>$JAR</string>
  </array>
  <key>WorkingDirectory</key><string>$ROOT/service</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <!-- Wait between restarts, so a broken build doesn't spin. -->
  <key>ThrottleInterval</key><integer>15</integer>
  <!-- The capture process outlives a service restart mid-meeting and is re-attached (PRD §10). -->
  <key>AbandonProcessGroup</key><true/>
  <key>StandardOutPath</key><string>$ROOT/data/logs/service.log</string>
  <key>StandardErrorPath</key><string>$ROOT/data/logs/service.log</string>
</dict>
</plist>
EOF
}

loaded() { launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; }

# A copy started by hand (nohup) holds the port: stop it before launchd starts its own.
stop_unmanaged() {
  PID=$(lsof -ti tcp:8090 -sTCP:LISTEN || true)
  if [ -n "$PID" ] && ! loaded; then
    kill "$PID" && while kill -0 "$PID" 2>/dev/null; do sleep 0.5; done
  fi
}

wait_up() {
  for _ in $(seq 1 60); do
    curl -s localhost:8090/api/health | grep -q '"ml":"up"' && echo up && return 0
    sleep 2
  done
  echo "service did not come up; see data/logs/service.log"
  return 1
}

case "$1" in
  install)
    [ -f "$JAR" ] || (cd "$ROOT/service" && JAVA_HOME=$(java_home) mvn -q -DskipTests package)
    stop_unmanaged
    loaded && launchctl bootout "$DOMAIN/$LABEL" || true
    write_plist
    launchctl bootstrap "$DOMAIN" "$PLIST"
    wait_up
    ;;
  uninstall)
    loaded && launchctl bootout "$DOMAIN/$LABEL" || true
    rm -f "$PLIST"
    echo "removed; start by hand with scripts/restart-service.sh"
    ;;
  restart)
    launchctl kickstart -k "$DOMAIN/$LABEL"
    wait_up
    ;;
  stop)
    # bootout, not kill: with KeepAlive, a killed service comes straight back.
    launchctl bootout "$DOMAIN/$LABEL"
    echo "stopped until 'scripts/earshot.sh start' or the next login"
    ;;
  start)
    loaded || launchctl bootstrap "$DOMAIN" "$PLIST"
    wait_up
    ;;
  status)
    if loaded; then echo "launchd: loaded ($LABEL)"; else echo "launchd: not loaded"; fi
    curl -s -m 3 localhost:8090/api/health || echo "service: not answering"
    echo
    ;;
  *)
    sed -n '2,10p' "$0"
    exit 2
    ;;
esac
