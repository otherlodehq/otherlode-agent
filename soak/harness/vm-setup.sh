#!/usr/bin/env bash
# It is safe to run again.
# It installs a JDK, not a JRE, because the sampler runs jcmd.
set -euo pipefail

if [ "$(id -u)" != 0 ]; then
  exec sudo -E "$0" "$@"
fi

home=${SOAK_HOME:-/opt/soak}
export DEBIAN_FRONTEND=noninteractive

log() { printf 'vm-setup: %s\n' "$*"; }

log "installing packages"
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg python3 procps openssl docker.io
systemctl enable --now docker

install_temurin() {
  install -d -m 0755 /etc/apt/keyrings
  curl -fsSL --max-time 60 https://packages.adoptium.net/artifactory/api/gpg/key/public |
    gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
  local codename
  codename=$(. /etc/os-release && echo "$VERSION_CODENAME")
  printf 'deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb %s main\n' \
    "$codename" >/etc/apt/sources.list.d/adoptium.list
  apt-get update -qq
  apt-get install -y -qq temurin-21-jdk
}

if ! command -v jcmd >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  log "installing a JDK"
  if install_temurin; then
    log "installed Temurin 21"
  else
    log "Temurin is not reachable; installing Debian's OpenJDK 17"
    rm -f /etc/apt/sources.list.d/adoptium.list
    apt-get update -qq
    apt-get install -y -qq openjdk-17-jdk-headless
  fi
fi

if ! id soak >/dev/null 2>&1; then
  log "creating the user soak"
  useradd --system --home-dir "$home" --no-create-home --shell /usr/sbin/nologin soak
fi
usermod -aG docker soak
mkdir -p "$home"/{bin,agent,apps,harness,logs,state,reports,secrets,run}
chown -R soak:soak "$home"
chmod 700 "$home/secrets"
if [ -d "$home/harness" ]; then
  chmod +x "$home"/harness/*.sh "$home"/harness/*.py 2>/dev/null || true
  ln -sf "$home/harness/soakctl.sh" /usr/local/bin/soakctl
fi

log "writing the systemd units"
cat >/etc/systemd/system/soak-stack.service <<EOF
[Unit]
Description=Otherlode soak stack (Postgres, server, collector)
After=docker.service network-online.target
Requires=docker.service
ConditionPathExists=$home/state/run.env

[Service]
Type=oneshot
RemainAfterExit=yes
User=soak
Group=soak
Environment=SOAK_HOME=$home
ExecStart=$home/harness/stack.sh up
ExecStop=$home/harness/stack.sh down
KillMode=process
TimeoutStartSec=900
TimeoutStopSec=300

[Install]
WantedBy=multi-user.target
EOF

for svc in server collector refresh; do
  after=""
  case $svc in
    server) cmd=run-server ;;
    collector) cmd=run-collector after=" soak-server.service" ;;
    refresh) cmd=refresh-loop ;;
  esac
  cat >/etc/systemd/system/soak-$svc.service <<EOF
[Unit]
Description=Otherlode soak $svc
After=docker.service network-online.target$after
Before=soak.service

[Service]
User=soak
Group=soak
Environment=SOAK_HOME=$home
ExecStart=$home/harness/stack.sh $cmd
Restart=no
TimeoutStopSec=60
EOF
done

# The user soak may start, stop and restart its three stack units, and nothing else as root.
rules=""
for svc in server collector refresh; do
  for verb in start stop restart; do
    rules="$rules${rules:+, }/usr/bin/systemctl $verb soak-$svc.service"
  done
done
printf 'soak ALL=(root) NOPASSWD: %s\n' "$rules" >/etc/sudoers.d/soak.tmp
chmod 440 /etc/sudoers.d/soak.tmp
visudo -cf /etc/sudoers.d/soak.tmp >/dev/null
mv /etc/sudoers.d/soak.tmp /etc/sudoers.d/soak

cat >/etc/systemd/system/soak.service <<EOF
[Unit]
Description=Otherlode soak supervisor
After=soak-stack.service
Requires=soak-stack.service
ConditionPathExists=$home/state/run.env

[Service]
User=soak
Group=soak
WorkingDirectory=$home
Environment=SOAK_HOME=$home
EnvironmentFile=$home/state/run.env
ExecStart=/usr/bin/python3 $home/harness/soak.py \$SOAK_ARGS
Restart=on-failure
RestartSec=10
KillMode=mixed
TimeoutStopSec=300

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable soak-stack.service soak.service >/dev/null 2>&1 || true

log "done"
java -version 2>&1 | head -1 | sed 's/^/vm-setup: java: /'
printf 'vm-setup: jcmd: %s\n' "$(command -v jcmd || echo missing)"
docker --version | sed 's/^/vm-setup: /'
python3 --version | sed 's/^/vm-setup: /'
