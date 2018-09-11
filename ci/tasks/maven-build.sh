#!/bin/bash
cd sb_source

mvn install 

cp target/*.jar ../jar/osb_pks.jar
