#!/bin/bash

# PATH TO FILE CONFIG.JSON
CONFIG="network/config.json"

echo "Compiling Project ..."
mvn clean install -DskipTests

REPLICAS=("p2" "p3" "p4")
CLIENTS=("p1")

PIDS=()

echo "Starting Replicas ..."
for id in "${REPLICAS[@]}"; do
  echo "Starting replica $id"
  mvn exec:java -pl network \
    -Dexec.mainClass="ist.depchain.network.Usage" \
    -Dexec.args="$CONFIG $id" \
    > "replica_$id.log" 2>&1 &

  PIDS+=($!)
done

sleep 3

#echo "Starting Clients ..."
#for id in "${CLIENTS[@]}"; do
#  mvn exec:java -pl client \
#    -Dexec.mainClass="ist.depchain.client.ClientApp" \
#    -Dexec.args="$CONFIG $id"
#done

echo "Cleaning up replicas..."
for pid in "${PIDS[@]}"; do
  kill "$pid" 2>/dev/null
done

echo "Done."