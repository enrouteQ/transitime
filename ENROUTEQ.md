```
docker run --rm -it --network transitclock_default -v ./configs:/usr/local/transitclock/config -v ./tomcat-logs:/usr/local/tomcat/logs -p 8080:8080 tc:web catalina
```

```
docker run --rm  -it --network transitclock_default -v ./configs:/usr/local/transitclock/config -v ./logs:/usr/local/transitclock/logs --name core tc:test core
```