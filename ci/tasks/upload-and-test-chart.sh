#!/bin/bash

# SOURCE FUNCTIONS FROM HELPERS
source osb-pks-ci/ci/tasks/helper.sh
source osb-pks-ci/ci/tasks/prepare.sh
prepare

# RECOVER CLUSTER DATA FROM PREVIOUS JOBS
source <(cat test-cluster-data/keyval.properties | grep -v '^UPDATED=')
KIBOSH_FQDN=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.fqdn"')
KIBOSH_PORT=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.port"')
KIBOSH_USER=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.user"')
KIBOSH_PASSWORD=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.password"')
KIBOSH_PREFIX=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."kibosh.protocol"')


BAZAAR_FQDN=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."bazaar.fqdn"')
BAZAAR_PORT=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."bazaar.port"')
BAZAAR_USER=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."bazaar.user"')
BAZAAR_PASSWORD=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."bazaar.password"')
BAZAAR_PREFIX=$(echo $SI_CREDENTIALS | jq -r '.credentials.k8s_context."pks-config-map"."bazaar.protocol"')

export SB_BROKER_URL="$KIBOSH_PREFIX$KIBOSH_FQDN:$KIBOSH_PORT"
export SB_BROKER_USERNAME=$KIBOSH_USER
export SB_BROKER_PASSWORD=$KIBOSH_PASSWORD

#IF $HOSTS IS SET, WE NEED TO FAKE DNS HERE AS WELL, THIS WILL APPEND TO LAST LINE OF HOSTS 

if [ -n "$HOSTS" ]; then
  GO_ROUTER_IP=$(cat /etc/hosts | grep 'GOROUTER' | head -1 | sed 's/ .*$//g')
  echo "$GO_ROUTER_IP $KIBOSH_FQDN" >> /etc/hosts
  echo "$GO_ROUTER_IP $BAZAAR_FQDN" >> /etc/hosts
fi

if $SKIP_TLS; then
  echo -e "$PCF_CA_CERT" > /usr/local/share/ca-certificates/pcf.crt
  update-ca-certificates
fi
bazaar="bzr -t $BAZAAR_PREFIX$BAZAAR_FQDN:$BAZAAR_PORT -u $BAZAAR_USER -p $BAZAAR_PASSWORD"

$bazaar save $CHART_TGZ_PATH

$bazaar list


# | Meaning
#remove first line of table that contains headers
#remove empty lines from output 
#remove last line that contains comment
SERVICE_CATALOG="$(eden catalog | tail -n +2 | grep -v '^$\|======' | head -n -1)"

SERVICE_NAME="$(echo -e "$SERVICE_CATALOG" | awk '{print $1}' | head -1 )"
SERVICE_PLANS=($(echo -e "$SERVICE_CATALOG" | awk '{print $2}'))
IFS=$'\n' PLAN_DESCRIPTIONS=($(echo -e "$SERVICE_CATALOG" | awk '{for(i=3;i<NF;i++)printf"%s",$i OFS;if(NF)printf"%s",$NF;printf ORS}' ))
i=0


echo "CHART CONTAINS SERVICE: $SERVICE_NAME"
echo "--"
echo "CHART CONTAINS PLANS:"
for PLAN in ${SERVICE_PLANS[@]}; do
  echo -e "-----\nPLAN $i: $PLAN.\nDESCRIPTION: ${PLAN_DESCRIPTIONS[$i]}"
  ((i=+1))
done

TEST_INSTANCE=$(eden provision -s $SERVICE_NAME -p "${SERVICE_PLANS[0]}" | grep 'name: ' | sed 's/.*name: //')
eden bind -i "$TEST_INSTANCE"
TEST_INSTANCE_BINDING=$(eden credentials -i "$TEST_INSTANCE" | jq .)

echo "$TEST_INSTANCE_BINDING" | jq .

$CHART_TEST_SCRIPT_PATH #execute provided test script


