FROM eclipse-temurin:21-jdk AS build

ARG MODULE_NAME
ARG MODULE_PATH

WORKDIR /workspace

COPY common-module ./common-module
COPY ${MODULE_PATH} ./${MODULE_PATH}

RUN chmod +x ./${MODULE_PATH}/gradlew
RUN ./${MODULE_PATH}/gradlew -p ./${MODULE_PATH} clean bootJar --no-daemon

FROM eclipse-temurin:21-jre

ARG MODULE_NAME
ARG MODULE_PATH

WORKDIR /app

COPY --from=build /workspace/${MODULE_PATH}/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
