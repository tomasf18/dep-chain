#!/bin/bash

# PATH TO FILE CONFIG.JSON
CONFIG="network/config.json"

echo "Compiling Project ..."
mvn clean install -DskipTests

REPLICAS=("p2" "p3" "p4")
CLIENTS=("p1")

echo "Starting Replicas ..."
for id in "${REPLICAS[@]}"; do
  osascript -e "tell application \"Terminal\" to do script \"cd '$(pwd)'; mvn exec:java -pl network -Dexec.mainClass='ist.depchain.network.Usage' -Dexec.args='$CONFIG $id'\""
done

echo "Starting Clients ..."
for id in "${CLIENTS[@]}"; do
  mvn exec:java -pl client -Dexec.mainClass="ist.depchain.client.ClientApp" -Dexec.args="$CONFIG $id"
done

echo "Cleaning up background processes..."
kill $(jobs -p)