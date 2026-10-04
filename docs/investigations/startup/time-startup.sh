#!/bin/bash
# Starts spring-petclinic-rest once and prints the label, Spring Boot's "Started ... in" seconds and
# its "process running for" seconds.
#
# usage: time-startup.sh <label> [JVM options...]
#   e.g. time-startup.sh headline "-javaagent:$AGENT_JAR=includePackages=org.springframework.samples.petclinic,exportUrl=http://127.0.0.1:1"
#
# Needs, as environment variables:
#   JAVA          a JDK 21 java binary with a CDS archive (`java -Xshare:on -version` must work)
#   PETCLINIC_JAR spring-petclinic-rest's fat jar (see ../heap/measure.sh)
#   OUT_DIR       where each run's log goes
# and Postgres on localhost:55432 (user, password and database "petclinic"). Repeat each label five
# to seven times and take the median; the machine should be otherwise idle.
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
