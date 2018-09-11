#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS
source osb-pks/ci/tasks/helper.sh

if [ ! -z $HOSTS ]; then
  fake_dns
fi

# TEST CREATE CLUSTER
curl -X PUT  $SB/service_instances/$SI_ID \
  -H "Content-Type: application/json" \
  -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'", "parameters": {"provision_kibosh": false, "provision_default_operator": false}}'

cont=true
get_last_op

