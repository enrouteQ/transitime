#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset

[ -f /app/.gitcommit ] && echo "Git commit id" "$(cat /app/.gitcommit)"
[ -f /app/.gittag ] && echo "Git tag" "$(cat /app/.gittag)"

DD_TRACE_RUN=""
if [ "${DD_TRACE:-true}" != "false" ]; then
  DD_TRACE_RUN=ddtrace-run
fi

cmd=("$@")

if [ "${LAUNCH_TYPE:-FARGATE}" = "EC2" ]; then
  DD_AGENT_HOST="$(curl http://169.254.169.254/latest/meta-data/local-ipv4)"
  export DD_AGENT_HOST
fi

logging_dir="/usr/local/transitclock/logs"
gtfs_static_public_url="https://kamil-public-test-bucket.s3.us-west-2.amazonaws.com/maraliner.zip?"
config_file_path="/usr/local/transitclock/config/maraliner/maraliner.properties"

connection_host="mercury-transitclock-db-server-instance-1.c7x2jvfk5i1o.us-west-2.rds.amazonaws.com"
connection_database="postgres"
connection_user="postgres"
connection_password="TransitClock4dmin"
connection_url="jdbc:postgresql://$connection_host:5432/$connection_database"


copy_hibernate_config() {
  cp /usr/local/transitclock/config/hibernate.cfg.xml /transitclock/hibernate.cfg.xml
}

load() {
  copy_hibernate_config
  echo "Loading GTFS file..."
  java \
    -Dtransitclock.db.dbUserName=$connection_user \
    -Dtransitclock.db.dbPassword=$connection_password \
    -Dtransitclock.db.dbHost=$connection_host \
    -Dtransitclock.db.dbName=$connection_database \
    -Dtransitclock.logging.dir=$logging_dir \
    -cp Core.jar org.transitclock.applications.GtfsFileProcessor \
    -c $config_file_path \
    -storeNewRevs \
    -skipDeleteRevs \
    -gtfsUrl $gtfs_static_public_url \
    -maxTravelTimeSegmentLength 100
}

create_api_key() {
  copy_hibernate_config
  echo "Generating API Key..."
  java \
    -Dtransitclock.db.dbUserName=$connection_user \
    -Dtransitclock.db.dbPassword=$connection_password \
    -Dtransitclock.db.dbHost=$connection_host \
    -Dtransitclock.db.dbName=$connection_database \
    -Dtransitclock.logging.dir=$logging_dir \
    -cp Core.jar org.transitclock.applications.CreateAPIKey \
    -c $config_file_path \
    -n "Kris Appleseed" \
    -u "https://www.google.com" \
    -e "info@example.com" \
    -p "8005555555" \
    -d "Core access application"
}

create_agency() {
  copy_hibernate_config
  echo "Creating agency..."
  java \
    -Dtransitclock.logging.dir=$logging_dir \
    -Dhibernate.connection.url=$connection_url \
    -Dhibernate.connection.username=$connection_user \
    -Dhibernate.connection.password=$connection_password \
    -Dhibernate.connection.dialect=org.hibernate.dialect.PostgreSQLDialect \
    -Dhibernate.connection.connection.driver_class=org.postgresql.Driver \
    -Dtransitclock.hibernate.configFile="hibernate.cfg.xml" \
    -cp Core.jar org.transitclock.db.webstructs.WebAgency \
    Maraliner \
    127.0.0.1 \
    $connection_database \
    postgresql \
    $connection_host \
    $connection_user \
    $connection_password
}

core() {
  copy_hibernate_config
  echo "Running core service..."
  rmiregistry & java \
    -Dtransitclock.configFiles=$config_file_path \
    -Dtransitclock.hibernate.configFile="hibernate.cfg.xml" \
    -Dtransitclock.db.dbUserName=$connection_user \
    -Dtransitclock.db.dbPassword=$connection_password \
    -Dtransitclock.db.dbHost=$connection_host \
    -Dtransitclock.db.dbName=$connection_database \
    -Dtransitclock.logging.dir=$logging_dir \
    -Dtransitclock.rmi.secondaryRmiPort=0 \
    -jar Core.jar
}


"$@" || exit $?
