#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS
source osb-pks/ci/tasks/helper.sh
source osb-pks/ci/tasks/prepare.sh
prepare

nohup java -jar osb-pks-pre-release/osb_pks.jar&
wait_for_osb

# TEST CREATE CLUSTER
curl -X PUT  $SB/service_instances/$SI_ID \
  -H "Content-Type: application/json" \
  -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'", "parameters": {"provision_kibosh": true, "provision_default_operator": false}}'

get_last_op

