#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS AND RUN PREPARE
source osb-pks/ci/tasks/helper.sh
source osb-pks/ci/tasks/prepare.sh
prepare

set -e
PKS_FQDN=$(echo $SPRING_APPLICATION_JSON | jq .pks.fqdn -r)
PCF_UAA_FQDN="uaa.$(echo $SPRING_APPLICATION_JSON | jq .pcf.sys -r)"
PCF_API_FQDN="api.$(echo $SPRING_APPLICATION_JSON | jq .pcf.sys -r)"

PKS_API_CERT=$(echo | openssl s_client -connect $PKS_FQDN:9021 -showcerts | openssl x509)
PKS_UAA_CERT=$(echo | openssl s_client -connect $PKS_FQDN:8443 -showcerts | openssl x509)
PCF_API_CERT=$(echo | openssl s_client -connect $PCF_API_FQDN:443 -showcerts | openssl x509)
PCF_UAA_CERT=$(echo | openssl s_client -connect $PCF_UAA_FQDN:443 -showcerts | openssl x509)

import_self_signed_certs "$PKS_API_CERT" "$PKS_UAA_CERT" "$PCF_API_CERT" "$PCF_UAA_CERT"

nohup java -jar osb-pks-pre-release/osb_pks.jar &
wait_for_osb

SI_ID=$(cat /proc/sys/kernel/random/uuid)  # SERVICE INSTANCE ID
echo "SI_ID=$SI_ID" > test-cluster-data/SI_ID.sh
set -o pipefail # Fail on non 0 returns in pipe chains
SB="http://admin:pass@localhost:8080/v2"
# TEST CATALOG FOR PROVIDING PROPER JSON
curl $SB/catalog  | jq .
PKS_ID=$(curl $SB/catalog | jq .services[0].id -r)
PKS_PLAN_ID=$(curl $SB/catalog | jq .services[0].plans[0].id -r)
# TEST CREATE CLUSTER
curl -X PUT  $SB/service_instances/$SI_ID \
  -H "Content-Type: application/json" \
  -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'", "parameters": {"provision_kibosh": false, "provision_default_operator": false}}'

cont=true
get_last_op

# UPDATE TO USE DEFAULT OPERATOR
curl -X PATCH  $SB/service_instances/$SI_ID \
  -H "Content-Type: application/json" \
  -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'", "parameters": {"provision_kibosh": false, "provision_default_operator": true}}'

cont=true
get_last_op

