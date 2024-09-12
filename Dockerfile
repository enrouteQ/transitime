FROM maven:3-amazoncorretto-17 AS build

WORKDIR /build

COPY . .

RUN mvn install -DskipTests

FROM tomcat:8.5.93-jre17 AS web

COPY --from=build /build/transitclockWebapp/target/web.war /usr/local/tomcat/webapps/
COPY --from=build /build/transitclockApi/target/api.war /usr/local/tomcat/webapps/

COPY entrypoint.tomcat.sh /usr/local/tomcat/entrypoint.tomcat.sh
RUN sed -i 's/\r//' /usr/local/tomcat/entrypoint.tomcat.sh \
    && chmod +x /usr/local/tomcat/entrypoint.tomcat.sh

ENTRYPOINT [ "/usr/local/tomcat/entrypoint.tomcat.sh" ]
CMD ["catalina"]

FROM amazoncorretto:17 AS core

WORKDIR /transitclock

COPY entrypoint.sh .
RUN sed -i 's/\r//' entrypoint.sh \
    && chmod +x entrypoint.sh

COPY --from=build /build/transitclock/target/Core.jar .
EXPOSE 1099
ENTRYPOINT [ "/transitclock/entrypoint.sh" ]
CMD ["core"]