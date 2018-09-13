#!/bin/bash
cd osb-pks

mvn install 

cp target/*.jar ../jar/osb_pks.jar
git log -1 --pretty=%B > ../jar/body
