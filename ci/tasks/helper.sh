#!/bin/bash
import_self_signed_certs(){
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
}
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
fake_dns(){
  echo -e $HOSTS >> /etc/hosts
}
wait_for_osb(){
  cont=true
  while $cont; do
    if nc -z localhost 8080; then
      cont=false
    else
     echo "waiting for build artifact to start..."
     sleep 1
    fi
  done
}
