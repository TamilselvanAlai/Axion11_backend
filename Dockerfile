FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# ExifTool reliably reads RAW camera metadata (CR3/CR2/NEF/ARW/etc.) across the wide variety of
# container structures different makes/models/firmware produce — pure-Java libraries like
# metadata-extractor have inconsistent RAW support, which is what previews being rotated wrong
# for some CR3 files traced back to (see ImageUploadService#readOrientationViaExifTool).
RUN apk add --no-cache exiftool
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
