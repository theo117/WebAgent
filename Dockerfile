FROM eclipse-temurin:25-jdk

WORKDIR /app

COPY src ./src

RUN mkdir -p out && \
    find src -name "*.java" -print0 | xargs -0 javac -d out

ENV PORT=10000

EXPOSE 10000

CMD ["java", "--add-modules", "jdk.httpserver", "-cp", "out", "webagent.Main"]
