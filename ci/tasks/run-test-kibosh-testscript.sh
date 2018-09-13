#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS
source osb-pks-ci/ci/tasks/helper.sh
source osb-pks-ci/ci/tasks/prepare.sh
prepare

# RECOVER CLUSTER DATA FROM PREVIOUS JOBS
source <(cat updated-test-cluster-data/keyval.properties | grep -v '^UPDATED=')
KIBOSH_FQDN=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.fqdn"')
KIBOSH_PORT=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.port"')
KIBOSH_USER=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.user"')
KIBOSH_PASSWORD=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.password"')
KIBOSH_PREFIX=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.protocol"')

export SB_BROKER_URL="$KIBOSH_PREFIX$KIBOSH_FQDN:$KIBOSH_PORT"
export SB_BROKER_USERNAME=$KIBOSH_USER
export SB_BROKER_PASSWORD=$KIBOSH_PASSWORD

#IF $HOSTS IS SET, WE NEED TO FAKE DNS HERE AS WELL, THIS WILL APPEND TO LAST LINE OF HOSTS 

if [ -n "$HOSTS" ]; then
  GO_ROUTER_IP=$(cat /etc/hosts | grep 'GOROUTER' | head -1 | sed 's/ .*$//g')
  echo "$GO_ROUTER_IP $KIBOSH_FQDN" >> /etc/hosts
fi

if $SKIP_TLS; then
  echo -e "$PCF_CA_CERT" > /usr/local/share/ca-certificates/pcf.crt
  update-ca-certificates
fi

eden catalog
