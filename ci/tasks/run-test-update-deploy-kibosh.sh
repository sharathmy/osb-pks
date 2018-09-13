#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS
source osb-pks-ci/ci/tasks/helper.sh
source osb-pks-ci/ci/tasks/prepare.sh
prepare

source empty-kubernetes-cluster-id/keyval.properties


PKS_FQDN=$(echo $SPRING_APPLICATION_JSON | jq .pks.fqdn -r)
PCF_UAA_FQDN="uaa.$(echo $SPRING_APPLICATION_JSON | jq .pcf.sys -r)"
PCF_API_FQDN="api.$(echo $SPRING_APPLICATION_JSON | jq .pcf.sys -r)"

PKS_API_CERT=$(echo | openssl s_client -connect $PKS_FQDN:9021 -showcerts | openssl x509)
PKS_UAA_CERT=$(echo | openssl s_client -connect $PKS_FQDN:8443 -showcerts | openssl x509)
PCF_API_CERT=$(echo | openssl s_client -connect $PCF_API_FQDN:443 -showcerts | openssl x509)
PCF_UAA_CERT=$(echo | openssl s_client -connect $PCF_UAA_FQDN:443 -showcerts | openssl x509)

import_self_signed_certs "$PKS_API_CERT" "$PKS_UAA_CERT" "$PCF_API_CERT" "$PCF_UAA_CERT" "$PCF_CA_CERT"
# START ARTIFACT
nohup java -jar osb-pks-pre-release/osb_pks.jar&
wait_for_osb
# TEST CREATE CLUSTER
curl -X PUT  $SB/service_instances/$SI_ID \
  -H "Content-Type: application/json" \
  -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'", "parameters": {"provision_kibosh": true, "provision_default_operator": false}}'

sleep 5

get_last_op

