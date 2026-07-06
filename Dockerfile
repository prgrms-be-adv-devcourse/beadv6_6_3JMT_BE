FROM eclipse-temurin:21-jdk AS build

ARG MODULE_NAME
ARG MODULE_PATH

WORKDIR /workspace

# 루트 Gradle 래퍼 및 빌드 스크립트 복사
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle

# 공통 모듈 및 대상 서비스 복사
COPY common-module ./common-module
COPY ${MODULE_PATH} ./${MODULE_PATH}

RUN chmod +x gradlew
RUN ./gradlew :${MODULE_PATH}:bootJar --no-daemon

FROM eclipse-temurin:21-jre

ARG MODULE_NAME
ARG MODULE_PATH

WORKDIR /app

COPY --from=build /workspace/${MODULE_PATH}/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
