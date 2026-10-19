#!/usr/bin/env bash
# Deploys the server to the VM with the deploy key, which can only run todo-deploy there
# (server/deploy/todo-deploy: backup, swap, restart, roll back if unhealthy).
# Usage: tools/deploy.sh            build and deploy
#        tools/deploy.sh status     service state, newest backup, recent warnings
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=~/.config/raspberry
vm() {
    ssh -i "$DIR/deploy_key" -o IdentitiesOnly=yes -o BatchMode=yes \
        -o UserKnownHostsFile="$DIR/known_hosts" -o StrictHostKeyChecking=accept-new \
        deploy@34.69.150.11 "$@"
}
if [ "${1:-}" = status ]; then vm status; exit; fi
./gradlew -q :server:distTar
vm deploy < server/build/distributions/server.tar
