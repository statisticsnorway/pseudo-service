FROM gcr.io/distroless/java25-debian13:debug
COPY target/pseudo-service-*.jar pseudo-service.jar
COPY src/main/resources/logback*.xml /conf/
COPY conf/bootstrap.yml /conf/
COPY conf/application.yml /conf/
COPY conf/application-sid-client.yml /conf/
EXPOSE 10210
ENTRYPOINT ["/usr/bin/java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005", "-Dcom.sun.management.jmxremote", "-Dmicronaut.bootstrap.context=true", "-Xmx8g", "-jar", "pseudo-service.jar"]
