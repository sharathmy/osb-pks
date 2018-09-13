#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS AND RUN PREPARE
source osb-pks-ci/ci/tasks/helper.sh
source osb-pks-ci/ci/tasks/prepare.sh
prepare

source test-cluster-data/keyval.properties


PKS_FQDN=$(echo $SPRING_APPLICATION_JSON | jq .pks.fqdn -r)
PCF_UAA_FQDN="uaa.$(echo $SPRING_APPLICATION_JSON | jq .pcf.sys -r)"
PCF_API_FQDN="api.$(echo $SPRING_APPLICATION_JSON | jq .pcf.sys -r)"

PKS_API_CERT=$(echo | openssl s_client -connect $PKS_FQDN:9021 -showcerts | openssl x509)
PKS_UAA_CERT=$(echo | openssl s_client -connect $PKS_FQDN:8443 -showcerts | openssl x509)
PCF_API_CERT=$(echo | openssl s_client -connect $PCF_API_FQDN:443 -showcerts | openssl x509)
PCF_UAA_CERT=$(echo | openssl s_client -connect $PCF_UAA_FQDN:443 -showcerts | openssl x509)

import_self_signed_certs "$PKS_API_CERT" "$PKS_UAA_CERT" "$PCF_API_CERT" "$PCF_UAA_CERT" "$PCF_CA_CERT"
SB=admin:pass@localhost:8080
set -e
nohup java -jar osb-pks-pre-release/osb_pks.jar &
wait_for_osb


SI_BINDING_ID=$(cat /proc/sys/kernel/random/uuid)
# CREATE BINDING
SI_CREDENTIALS=$(curl -s -X PUT  $SB/v2/service_instances/$SI_ID/service_bindings/$SI_BINDING_ID \
  -H "Content-Type: application/json" \
  -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'"}')

echo $SI_CREDENTIALS | jq .
#EXTRACT KUBE-CONFIG FROM CREDENTIALS JSON AND LET BOSH CLI PARSE IT TO YAML
bosh int <(echo $SI_CREDENTIALS | jq .credentials.k8s_context) > kube-config
#RUN TEST WITH KUBECTL
kubectl --kubeconfig ./kube-config get namespaces
#UPDATE TEST-CLUSTER-DATA
cp test-cluster-data/keyval.properties updated-test-cluster-data/keyval.properties
echo 'SI_BINDING_ID="'"$SI_BINDING_ID"'"' >> updated-test-cluster-data/keyval.properties
echo "SI_CREDENTIALS='"$SI_CREDENTIALS"'" >> updated-test-cluster-data/keyval.properties
