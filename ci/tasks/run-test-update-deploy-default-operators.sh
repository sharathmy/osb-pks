# UPDATE TO USE DEFAULT OPERATOR
curl -X PATCH  $SB/service_instances/$SI_ID \
  -H "Content-Type: application/json" \
  -d '{"service_id":"'$PKS_ID'", "plan_id": "'$PKS_PLAN_ID'", "parameters": {"provision_kibosh": false, "provision_default_operator": true}}'

cont=true
get_last_op

