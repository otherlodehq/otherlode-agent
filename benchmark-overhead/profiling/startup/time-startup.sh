#!/bin/bash
# usage: time-startup.sh <label> [JVM options...]
# Needs JAVA (a JDK 21 java binary with a CDS archive), PETCLINIC_JAR and OUT_DIR, with Postgres
# on localhost:55432 (user, password and database "petclinic").
set -u
label=$1; shift
mkdir -p "$OUT_DIR"
log=$OUT_DIR/$label.$(date +%s%N).log
export SPRING_PROFILES_ACTIVE=postgres,spring-data-jpa POSTGRES_URL=jdbc:postgresql://localhost:55432/petclinic \
  POSTGRES_USER=petclinic POSTGRES_PASS=petclinic
"$JAVA" "$@" -jar "$PETCLINIC_JAR" --server.port=0 > "$log" 2>&1 &
pid=$!
for i in $(seq 1 1200); do
  grep -q "Started PetClinicApplication" "$log" && break
  kill -0 $pid 2>/dev/null || { echo "$label died"; tail -20 "$log"; exit 1; }
  sleep 0.1
done
line=$(grep "Started PetClinicApplication" "$log")
kill $pid; wait $pid 2>/dev/null
started=$(echo "$line" | sed -E 's/.*in ([0-9.]+) seconds.*/\1/')
running=$(echo "$line" | sed -E 's/.*running for ([0-9.]+)\).*/\1/')
echo "$label $started $running $log"
