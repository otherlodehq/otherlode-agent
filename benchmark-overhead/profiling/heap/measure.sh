#!/bin/bash
# usage: measure.sh <label> <includePackages|none> <http port>
# Needs JAVA_HOME (a JDK 21), AGENT_JAR, PETCLINIC_JAR, OUT_DIR and optional DUMP=1, with Postgres
# running on localhost:25432 and the image otherlode-bench-collector built.
set -u
J=$JAVA_HOME/bin
L=$1; INC=$2; PORT=$3
OUT=$OUT_DIR/$L; mkdir -p "$OUT"
docker rm -f heap-collector >/dev/null 2>&1
AGENT=""
if [ "$INC" != none ]; then
  AGENT="-javaagent:$AGENT_JAR=includePackages=$INC,flushIntervalSeconds=5,exportUrl=http://localhost:14320,serviceName=petclinic-$L"
fi
SPRING_PROFILES_ACTIVE=postgres,spring-data-jpa POSTGRES_URL=jdbc:postgresql://localhost:25432/petclinic POSTGRES_USER=petclinic POSTGRES_PASS=petclinic \
  "$J/java" -Xmx1g -XX:+UseG1GC -XX:NativeMemoryTracking=summary $AGENT -jar "$PETCLINIC_JAR" --server.port="$PORT" > "$OUT/app.log" 2>&1 &
PID=$!
for i in $(seq 1 180); do grep -q "Started PetClinicApplication" "$OUT/app.log" && break; sleep 1; done
grep "Started PetClinicApplication" "$OUT/app.log" || { echo "PetClinic did not start"; tail -30 "$OUT/app.log"; exit 1; }
drive() {
  for r in $(seq 1 "$1"); do
    for p in owners vets pettypes specialties pets visits owners/1 owners/2 vets/1 pettypes/1 "owners?lastName=Davis"; do
      curl -s -o /dev/null "http://localhost:$PORT/petclinic/api/$p"
    done
  done
}
measure() {
  "$J/jcmd" $PID GC.run >/dev/null; sleep 1; "$J/jcmd" $PID GC.run >/dev/null
  "$J/jcmd" $PID GC.class_histogram > "$OUT/histo-$1.txt"
  "$J/jcmd" $PID GC.heap_info > "$OUT/heapinfo-$1.txt"
  "$J/jcmd" $PID VM.native_memory summary > "$OUT/nmt-$1.txt"
  ps -o rss= -p $PID > "$OUT/rss-$1.txt"
}
drive 30; sleep 3
measure undelivered
if [ "$INC" != none ]; then
  docker run -d --name heap-collector -e OTHERLODE_COLLECTOR_INSECURE_NO_AUTH=1 \
    -e OTHERLODE_COLLECTOR_RATE_LIMIT_RPS=0 -p 14320:4319 otherlode-bench-collector >/dev/null
fi
for k in 1 2 3 4 5 6; do drive 5; sleep 10; done
[ "$INC" != none ] && docker logs heap-collector > "$OUT/collector.log" 2>&1
measure delivered
[ "${DUMP:-0}" = 1 ] && "$J/jcmd" $PID GC.heap_dump "$OUT/heap-delivered.hprof" >/dev/null
kill $PID; wait $PID 2>/dev/null
docker rm -f heap-collector >/dev/null 2>&1
echo "done $L"
