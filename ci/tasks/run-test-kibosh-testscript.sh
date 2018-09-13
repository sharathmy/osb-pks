#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS
source osb-pks-ci/ci/tasks/helper.sh
source osb-pks-ci/ci/tasks/prepare.sh
prepare

# RECOVER CLUSTER DATA FROM PREVIOUS JOBS
source test-cluster-data/keyval.properties

KIBOSH_FQDN=$(echo $SI_CREDENTIALS | jq '.credentials.k8s_context."pks-config-map"."kibosh.fqdn"')
KIBOSH_PORT=$(echo $SI_CREDENTIALS | jq '.credentials.k8s_context."pks-config-map"."kibosh.port"')
KIBOSH_USER=$(echo $SI_CREDENTIALS | jq '.credentials.k8s_context."pks-config-map"."kibosh.user"')
KIBOSH_PASSWORD=$(echo $SI_CREDENTIALS | jq '.credentials.k8s_context."pks-config-map"."kibosh.password"')
KIBOSH_PREFIX=$(echo $SI_CREDENTIALS | jq '.credentials.k8s_context."pks-config-map"."kibosh.protocoll"')

export SB_BROKER_URL="$KIBOSH_PREFIX$KIBOSH_FQDN:$KIBOSH_PORT"
export SB_BROKER_USERNAME=$KIBOSH_USER
export SB_BROKER_PASSWORD=$KIBOSH_PASSWORD


eden catalog
