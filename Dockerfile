FROM oracle/graalvm-ce:20.2.0-java11 as graalvm
RUN gu install native-image

COPY . /home/app/bookviewer
WORKDIR /home/app/bookviewer

RUN native-image -cp build/libs/bookviewer-*-all.jar

FROM frolvlad/alpine-glibc
RUN apk update && apk add libstdc++
EXPOSE 8080
COPY --from=graalvm /home/app/bookviewer/bookviewer /app/bookviewer
ENTRYPOINT ["/app/bookviewer"]
