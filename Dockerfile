
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY ecoai-grid-tuned.jar app.jar

ENV JAVA_OPTS="-Xms64m -Xmx320m"

EXPOSE 8080

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar demo --port ${PORT:-8080} --lan"]
