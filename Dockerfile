FROM eclipse-temurin:21-jdk AS build

ARG MODULE_NAME
ARG MODULE_PATH

WORKDIR /workspace

# 멀티모듈 루트 settings.gradle 이 전체 모듈을 include 하므로 소스 전체를 복사한다.
# 일부 모듈만 복사하면 컨테이너에 없는 모듈 디렉토리에서 Gradle 설정이 실패한다.
# (.dockerignore 가 .git·.idea·docs·style·**/build·**/.gradle 을 제외한다)
COPY . .

RUN chmod +x gradlew
RUN ./gradlew :${MODULE_PATH}:bootJar --no-daemon

FROM eclipse-temurin:21-jre

ARG MODULE_NAME
ARG MODULE_PATH

ENV TZ=Asia/Seoul

WORKDIR /app

COPY --from=build /workspace/${MODULE_PATH}/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
