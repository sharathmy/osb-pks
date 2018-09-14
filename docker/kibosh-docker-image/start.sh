#!/bin/bash
set -e -x -o pipefail
export KIBOSH_PATH="/go/src/github.com/cf-platform-eng/kibosh-master"
export CA_DATA="$(cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt)"
export SERVER=https://$KUBERNETES_PORT_443_TCP_ADDR:443
export TOKEN="$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"

export KIBOSH_SERVER=http://localhost:8080

export HELM_CHART_DIR="/home/charts/"
cd /home

# UNLIKE SPECIFIED IN DOCUMENTATION BOTH KIBOSH AND BAZAAR TAKE $SECURITY_USER_NAME/PASSWORD VALUES ON STARTUP
export SECURITY_USER_NAME=${BAZAAR_USER:admin}
export SECURITY_USER_PASSWORD=${BAZAAR_PASSWORD:pass}
export KIBOSH_USER_NAME=${KIBOSH_USER:admin} #FOR BAZAAR
export KIBOSH_USER_PASSWORD=${KIBOSH_PASSWORD:pass} # FOR BAZAAR

/usr/bin/bazaar &> /bazaar.log&

export SECURITY_USER_NAME=${KIBOSH_USER:admin}
export SECURITY_USER_PASSWORD=${KIBOSH_PASSWORD:pass}
/usr/bin/kibosh &> /kibosh.log&

tail -f /kibosh.log
