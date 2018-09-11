#!/bin/bash
get_last_op(){
  while $cont; do
    LAST_OP_JSON=$(curl -s -X GET  $SB/service_instances/$SI_ID/last_operation)
    STATE=$(echo $LAST_OP_JSON | jq .state -r)
    case "$STATE" in
    "error")
       cont=false
       #curl -s -X PUT  $SB/service_instances/$SI_ID \
            -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'"}'
       exit 1
     ;;
    "in progress")
       echo "$LAST_OP_JSON"
    ;;
    "succeeded")
       cont=false
       exit 0
    ;;
    *)
      echo "i got confused"
      echo $LAST_OP_JSON
      exit 1
    esac
    sleep 5
  done
}
set -e
echo -e "10.213.10.52 tcp.pcf21.starkandwayne.com\n10.213.10.3 api.pks.pcf21.starkandwayne.com" >> /etc/hosts
PKS_FQDN=$(echo $SPRING_APPLICATION_JSON | jq .pks.fqdn -r)


PKS_API_CERT=$(echo | openssl s_client -connect $PKS_FQDN:9021 -showcerts | openssl x509)
PKS_UAA_CERT=$(echo | openssl s_client -connect $PKS_FQDN:8443 -showcerts | openssl x509)


# TODO MAKE OPTIONAL
keytool -keystore $(readlink -f /usr/bin/java | sed "s:bin/java::")lib/security/cacerts \
  -importcert \
  -storepass changeit  \
  -file <(echo -e "$PKS_UAA_CERT") \
  -alias pks_api -noprompt
keytool -keystore $(readlink -f /usr/bin/java | sed "s:bin/java::")lib/security/cacerts \
  -importcert \
  -storepass changeit  \
  -file <(echo -e "$PKS_API_CERT") \
  -alias pks_uaa -noprompt
keytool -keystore $(readlink -f /usr/bin/java | sed "s:bin/java::")lib/security/cacerts \
  -importcert \
  -storepass changeit  \
  -file <(echo -e "$PCF_CA_CERT") \
  -alias pcf_uaa -noprompt

nohup java -jar jar/pks_sb.jar &
cont=true
while $cont; do
  if nc -z localhost 8080; then
    cont=false
  else
   echo "waiting for build artifact to start..."
   sleep 1
  fi
done
SI_ID=$(cat /proc/sys/kernel/random/uuid)  # SERVICE INSTANCE ID

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

