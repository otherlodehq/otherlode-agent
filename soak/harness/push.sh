#!/usr/bin/env bash
# Copies a bundle to the VM and installs it under /opt/soak. Secrets, state, logs and reports
# stay where they are; bin, agent, apps, harness and truth.json are replaced.
#
#   push.sh [bundle-dir] [--setup]
#
# --setup also runs vm-setup.sh on the VM. Do that on the first push.
# SOAK_VM, SOAK_PROJECT and SOAK_ZONE override the VM.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
agent_dir=$(cd "$here/../.." && pwd)
vm=${SOAK_VM:-soak-1}
project=${SOAK_PROJECT:-project-1d40b09b-5fc7-4240-bed}
zone=${SOAK_ZONE:-europe-west2-a}

bundle=$agent_dir/build/soak-bundle
setup=0
for arg in "$@"; do
  case "$arg" in
    --setup) setup=1 ;;
    -*) echo "unknown option $arg" >&2; exit 2 ;;
    *) bundle=$arg ;;
  esac
done

[ -f "$bundle/VERSION" ] && [ -f "$bundle/truth.json" ] || {
  echo "push: $bundle is not a bundle; run build-bundle.sh first" >&2
  exit 1
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/soak-push.XXXXXX")
tarball=$tmp/soak-bundle.tgz
tar -C "$bundle" -czf "$tarball" .
echo "push: $(du -h "$tarball" | cut -f1) to $vm"

gcloud compute scp --project="$project" --zone="$zone" "$tarball" "$vm:/tmp/soak-bundle.tgz"

gcloud compute ssh "$vm" --project="$project" --zone="$zone" --command='bash -s' <<'REMOTE'
set -euo pipefail
sudo mkdir -p /opt/soak
sudo rm -rf /opt/soak/bin /opt/soak/agent /opt/soak/apps /opt/soak/harness
sudo tar -xzf /tmp/soak-bundle.tgz -C /opt/soak --no-same-owner
sudo rm -f /tmp/soak-bundle.tgz
if id soak >/dev/null 2>&1; then sudo chown -R soak:soak /opt/soak; fi
sudo chmod +x /opt/soak/harness/*.sh /opt/soak/harness/*.py /opt/soak/bin/*
echo "installed:"
cat /opt/soak/VERSION
REMOTE

if [ "$setup" = 1 ]; then
  gcloud compute ssh "$vm" --project="$project" --zone="$zone" --command='sudo /opt/soak/harness/vm-setup.sh'
fi

find "$tmp" -mindepth 1 -delete
rmdir "$tmp"
echo "push: done"
