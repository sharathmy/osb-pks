#!/bin/bash
import_self_signed_certs(){
  set -x
  i=0
  for CERT in "$@"; do

    keytool -keystore $(readlink -f /usr/bin/java | sed "s:bin/java::")lib/security/cacerts \
      -importcert \
      -storepass changeit  \
      -file <(echo -e "$CERT") \
      -alias "cert_$1" -noprompt
  (( i += 1 ))
  done
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
  i=0
  while true; do
    if  [ "$(echo $HOSTS | jq ".[$i]")" != null ]; then
      echo $HOSTS | jq ".[$i]" -r >> /etc/hosts
      (( i += 1 ))
    else
      break
    fi
  done
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
