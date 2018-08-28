#!/bin/sh
#set -e -x
refresh_token(){
  export TOKEN=$(curl -s -k -X POST \
    "$UAA_URL/oauth/token" \
    -d "client_id=$ROUTE_CLIENT&client_secret=$ROUTE_CLIENT_SECRET&grant_type=client_credentials" | jq -r .access_token)
}
tcp_emit(){
  while (true); do
    refresh_token;
    export TCP_ROUTER_GROUP_ID=$(curl -s -k -H "Authorization: Bearer $TOKEN" https://$CF_API_FQDN/routing/v1/router_groups | jq -r '.[]|select(.type="tcp").guid')
    export MEMBER_INDEX=0 
    IFS=","
    for IP in $SERVICE_IP; do
      export ROUTE_CONFIG="[{
      \"router_group_guid\": \"$TCP_ROUTER_GROUP_ID\",
      \"port\": $TCP_ROUTER_PORT,
      \"backend_ip\": \"$IP\",
      \"backend_port\": $SERVICE_PORT,
      \"ttl\": 120,
      \"modification_tag\":  {
        \"guid\": \"$SERVICE_ID-$MEMBER_INDEX\",
        \"index\": $INDEX
      }
      }]"
      MEMBER_INDEX=$((MEMBER_INDEX+1))
    curl -s -k -H "Authorization: Bearer $TOKEN" -X POST https://$CF_API_FQDN/routing/v1/tcp_routes/create -d "$ROUTE_CONFIG"
    echo "registered route for $ROUTE_CONFIG"
    done #for
    sleep 30;
    INDEX=$((INDEX+1))
  done #while
}
http_emit(){
  while (true); do
    refresh_token;   
    IFS=','
    for IP in $SERVICE_IP; do
      ROUTE_CONFIG='[{"route":"'$CF_APPS_FQDN'", "ip":"'$IP'", "port":'$SERVICE_PORT', "ttl":120}]'
      curl -s -k -H "Authorization: Bearer $TOKEN" -X POST https://$CF_API_FQDN/routing/v1/routes -d "$ROUTE_CONFIG"
      echo "$ROUTE_CONFIG"
    done #for
  sleep 20;
  done #while
}
INDEX=0;
export UAA_URL=$(curl -s -k https://$CF_API_FQDN/v2/info  | jq .authorization_endpoint -r )
export TOKEN=$(curl -s -k -X POST "$UAA_URL/oauth/token" \
  -d "client_id=$ROUTE_CLIENT&client_secret=$ROUTE_CLIENT_SECRET&grant_type=client_credentials" | jq -r .access_token)
if [ -z "$CF_APPS_FQDN" ]; then
  tcp_emit
else
  http_emit
fi

