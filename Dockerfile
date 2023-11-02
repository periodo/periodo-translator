FROM --platform=linux/amd64 eclipse-temurin:21-jre

RUN mkdir /to_ttl
RUN mkdir /to_csv
RUN mkdir /err
RUN mkdir /out

COPY daemon/build/libs/daemon-all.jar /daemon.jar
COPY server/build/libs/server-all.jar /server.jar
COPY ./entrypoint.sh /

ENTRYPOINT ["./entrypoint.sh"]
