#!/bin/bash
set -e -x -o pipefail
export KIBOSH_PATH="/go/src/github.com/cf-platform-eng/kibosh-master"
export CA_DATA="$(cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt)"
export SERVER=https://$KUBERNETES_PORT_443_TCP_ADDR:443
export TOKEN="$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"

export KIBOSH_SERVER=http://localhost:8080
export PORT=8081 #BAZAAR PORT

export SECURITY_USER_NAME=${BAZAAR_USER:admin}
export SECURITY_USER_PASSWORD=${BAZAAR_PASSWORD:pass}
export KIBOSH_USER_NAME=${KIBOSH_USER:admin}
export KIBOSH_USER_PASSWORD=${KIBOSH_PASSWORD:pass}

export HELM_CHART_DIR="/home/charts/"
cd $HELM_CHART_DIR
/usr/bin/bazaar &> /bazaar.log&
/usr/bin/kibosh &> /kibosh.log&

tail -f /kibosh.log
