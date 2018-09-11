#!/bin/bash
cd sb_source

mvn install 

cp target/*.jar ../jar/pks_sb.jar

