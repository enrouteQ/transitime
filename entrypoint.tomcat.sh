#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset


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
gtfs_static_public_url="https://mimo-gtfs.s3.us-west-2.amazonaws.com/maraliner.zip?response-content-disposition=inline&X-Amz-Security-Token=IQoJb3JpZ2luX2VjEGIaCXVzLXdlc3QtMiJHMEUCIBI5NxoWvLDLtIxTaHobPxDPXp9UhRV3Ylj5sCh1yrLuAiEAwzNKOYrOSssxCF9LwM1BHl%2BFS%2B4jNuUUOjbFb1krkZIq%2FgMIi%2F%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FARABGgw1MjA1MDU1MjE1NzciDPS%2FG%2FIGqcubB8Pv8yrSA3oVKS8AoMn5PfNl%2B1uiW3JsgR%2Bjjwzwi0DnfbE%2Fw1TwfSMkkgZb%2BzwBqZx8%2BbXPdJkuHKDSU9Ib0s%2FF2D9Tjbx%2BevIGOqoP5gw8w9rXgH44g5HjDqxq803gi9NQqbrGUdiEMHLHOPxn1hnVTpRykhZIgSvhhRgwfVOAQZ%2F%2BfTxGmyq0348pHkuCHx8ie28rTl1RQN1STyFaM5eAQN6bs4yReNRJZWPyTQ1CQS9qyslvkzyaVtf%2Fxnso1ztEaMkjcJ6vzeCYyBp9eye9YJQiNt8Y7CLZVFd%2BZVJNW81UffMwqo7C7WTDn%2FiPcIcEdNaok%2Fpr9YvMx3SL8LXrmp%2BfEIQ981bTROnJ8jUCjVea89X%2F58T7D80d4%2FSNb%2BYseBlQIV2Cs4LlGEepRC0xiuH8L6aZ5MQF%2FlHyg7A7zzbVX1EuJQ9IlFfRqfl%2F2KlVsOH0ibQ9%2Bof1dccS2NLHpQZgbIWguidc84Likx4LSjTPAqjrIBMm8LlI0xloN8b1oaZlDJEmEl0gw4I9l0Kwz2MU%2B9lJnOCe206c0732eQY6elI5L2wJnLy%2FCXSDi7VW%2FuIHpNlqmKLhMnbPp5kOgg%2F5d4Cn3BeKBr%2FZBxDASR1P5Uf%2BJS4ws9SFtwY6lAKSo3EZEVYHXPF4d2VagTnokh88OVU%2Brl1i18QR7%2BPE3AmoU7Cit7YR3ea2vGZXW%2F3F7cXw4Kxhtu%2FYYdT7X3BpKuV6O6mfQbFI%2FeumAmSNKwtXnzxeRKTln4%2BZQrHg8DbqBqoGFV8PSrHIj6os3ehm5PToJyRmPBJtviQg5y7fBCPRhloQ%2B9htAEu%2BlHLUEJhn%2FpWv6kU6QRNuNZWsRSwcXwXdj5oQ0XbeJieD92V%2FZwBlHHllFTl8dBh%2Fkp6EXmq55Z%2FwmHDRKgmNyxDpe4Ejcuy%2BujTGkeVfkkJuMWcPP%2B2BcW6G8KcmXMB61jtYbwal%2F1P4S4i2xSRl0XiLQ2bZB%2FDQrCeojnBdn0mt3XM0QDTyQyY%3D&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20240911T100103Z&X-Amz-SignedHeaders=host&X-Amz-Expires=43200&X-Amz-Credential=ASIAXSMEL6WU7MYFMJGW%2F20240911%2Fus-west-2%2Fs3%2Faws4_request&X-Amz-Signature=0c80852a7a9a4a1030789233177a519aaf592dbd0a5fee3f480348535d96a415"
config_file_path="/usr/local/transitclock/config/maraliner/maraliner.properties"

connection_host="database"
connection_database="postgres"
connection_user="postgres"
connection_password="p4ssword!"
connection_url="jdbc:postgresql://$connection_host:5432/$connection_database"

catalina() {
  echo "Running web services... "
  catalina_ops="-Dtransitclock.apikey="aca5c746" \
  -Dtransitclock.db.dbUserName=$connection_user \
  -Dtransitclock.db.dbPassword=$connection_password \
  -Dtransitclock.db.dbHost=$connection_host \
  -Dtransitclock.db.dbName=$connection_database \
  -Dtransitclock.configFiles=/usr/local/transitclock/config/maraliner/maraliner.properties \
  -Dtransitclock.hibernate.configFile=/usr/local/transitclock/config/hibernate.cfg.xml"

  export JAVA_OPTS="$catalina_ops"
  export CATALINA_OPTS="$catalina_ops"
  echo $CATALINA_OPTS
  echo "run catalina"
  catalina.sh "run"
}


"$@" || exit $?
