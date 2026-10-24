#!/bin/sh
# Dev helper: rebuild and restart only Earshot's service (found by its port, never by jar name).
# Under launchd (scripts/earshot.sh install) the agent restarts it; otherwise it runs with nohup.
set -e
cd "$(dirname "$0")/../service"
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn -q -DskipTests package
if launchctl print "gui/$(id -u)/com.earshot.service" >/dev/null 2>&1; then
  exec ../scripts/earshot.sh restart
fi
PID=$(lsof -ti tcp:8090 -sTCP:LISTEN || true)
[ -n "$PID" ] && kill $PID && while kill -0 $PID 2>/dev/null; do sleep 0.5; done
LOG=${1:-../data/logs/service.log}
mkdir -p "$(dirname "$LOG")"
nohup "$JAVA_HOME/bin/java" -jar target/api-0.0.1-SNAPSHOT.jar >> "$LOG" 2>&1 &
for i in $(seq 1 60); do curl -s localhost:8090/api/health | grep -q '"ml":"up"' && echo up && exit 0; sleep 2; done
echo "service did not come up"; exit 1
