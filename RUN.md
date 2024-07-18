RT
```
curl --location https://0xmmxmujag.execute-api.us-west-2.amazonaws.com/v1/kenya-ktrans/VehiclePosition.pb' \
--header 'Content-Type: application/json' \

```

Insert GTFS Static

```
java \
  -Dtransitclock.logging.dir=tmp \
  -cp Core.jar org.transitclock.applications.GtfsFileProcessor \
  -c "ktrans.properties" \
  -storeNewRevs \
  -skipDeleteRevs \
  -gtfsUrl "https://mercury-ibi-datatools-gtfs-files.s3.us-west-2.amazonaws.com/kenya-ktrans.zip?response-content-disposition=inline&X-Amz-Security-Token=IQoJb3JpZ2luX2VjEDoaCXVzLXdlc3QtMiJHMEUCIQDKpUuSsb6wLqdoDuK5c1F4ZjGahyB35z2np65c5zOHUAIgLks9l3GNDcR0Wiu4euXh1FLsYX8I8bIu%2F07pUKMDE%2Bkq7wMIExABGgw5NzA3MDEwMzg4OTMiDOMv0I6uQaBKnuHqairMAxxp4Xm1BNf0orNKt9PioxQ5b7FXQL3EX%2FyGTPKPE4GxjdN0BMH0TvIVd4sGg%2FYkvc8yKmVrZDfIMPx%2FI4wgP9mBurovu4ywe4su0zq%2F0mkFUfzi8Gl2F9Tgu%2Fcpb9gcCHA6HNapiQWaPVisictxtVHxcsL8WNCBn8T87j1g3jR47Qu6W7ydXM9xN2uVA9CBPkvbonJY6o73DTxU4hVXHrRUzV4cJcS8m5rQmgG3amnl%2FvDEp4F6SaTCGobcrmkgUl%2B%2FxXpiUg0xP1GNyd3RbAay02lfQpZJ3941hVk%2Fhk96N1bLGQjS0Llos27Ar%2FukoLmJq0HoT8xt%2BNfPvR3wxs2ZQwpDkiCFR2cwlZbvEBcvE1x1o90H5OPUFKeYILcXX5RJaOBSCo9GZQ8IycsRP5Z8cCm8tVBpWzDr8hIEtak69dSL0NW0uIcUemjItnTD%2FZuLvHQOqZ0IFFNiFdOCRKCLc3L0uZaF7wlBaSndJjJacJpI5NadCcX9Ec%2FB3K9Nn2O8sNwYpN7kBuE%2BPRZiNblPOQbrnoGX9VxWo1t7HlimOV4CJFYHraFXa52qPdSdNDCVrtCI3ovIvtDN%2B3%2B7P8q4xzQaJVUNANj0viQwnsfjtAY6lAIGHh3KgZfi%2FJfrLjzcBx%2BEBDFzdvL6dE7Z%2BDIHdQLcm3RFxxAVyM5cBILeP%2FMLxuMsOGAtAtwFUN5%2Be0eFsprPbPfFsuYVrDMc3ZP0ngMJ4oa%2BRuA1Ms0GfN%2BtopdJ%2Bk95KZonPQ9N%2BuzdzBMZbs%2FqdgKnWnWtnI1z%2Fc49zYVEMeS6ynHSFsOQQpu4AixBloXOh6vtK9cEP3oo6Bqp7ncG3zZvDHj8MR96jwx9HmVJ9EOpm0lMNhgFwy6tyr8drS%2FxMmxZseL%2FfgjrU%2Fbkpa2IdEpnIt0dlh4sspierDvKh2JDHv2VEzBXOFPKmzG3bjkeNPgvEJZ9taKjv8MRpw9VxpdZ7U4XZLwIY%2FUUUKHp5KurlhY%3D&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20240718T094329Z&X-Amz-SignedHeaders=host&X-Amz-Expires=43200&X-Amz-Credential=ASIA6EASKMUWRBDLUDX5%2F20240718%2Fus-west-2%2Fs3%2Faws4_request&X-Amz-Signature=0bf7a8391584a251e64e04caeb955563dd4c1c9abedb31bab1a06cdeee2d9706" \
  -maxTravelTimeSegmentLength 100

```

API kEY

```
java \
  -cp Core.jar org.transitclock.applications.CreateAPIKey \
  -c "ktrans.properties" \
  -n "Kamilek" \
  -u "https://enrouteq.com" \
  -e "kamil.szymanski@enrouteq.com" \
  -p "8005555555" \
  -d "Core access application"
```

WEB AGENCY

```
java \
  -Dhibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
  -Dhibernate.connection.driver_class=org.postgresql.Driver \
  -Dhibernate.connection.dbHost=database \
  -Dhibernate.connection.url=jdbc:postgresql://database:5432/ktrans \
  -Dhibernate.connection.username=postgres \
  -Dhibernate.connection.password=p4ssword! \
  -cp Core.jar org.transitclock.db.webstructs.WebAgency \
  enrouteq \
  127.0.0.1 \
  ktrans \
  postgresql \
  0.0.0.0 \
  postgres \
  p4ssword!
```

RUN"

```
rmiregistry & java \
  -Dtransitclock.configFiles=ktrans.properties \
  -Dtransitclock.logging.dir=tmp \
  -Dtransitclock.rmi.secondaryRmiPort=0 \
  -jar Core.jar
```

```
export JAVA_OPTS="-Dtransitclock.apikey=12a40cc2 \
-Dtransitclock.configFiles=/workspace/transitclock/target/ktrans.properties \
-Dtransitclock.hibernate.configFile=/workspace/transitclock/target/hibernate.cfg.xml"

export CATALINA_OPTS="-Dtransitclock.apikey=12a40cc2 \
-Dtransitclock.configFiles=/workspace/transitclock/target/ktrans.properties \
-Dtransitclock.hibernate.configFile=/workspace/transitclock/target/hibernate.cfg.xml"

```