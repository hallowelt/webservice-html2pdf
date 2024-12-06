FROM maven:3.9.9-amazoncorretto-21 AS build
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN mvn clean package

FROM openjdk:21-jdk-slim
COPY --from=build /usr/src/app/target/html2pdf-1.0.0-SNAPSHOT.jar /app/html2pdf.jar
WORKDIR /app

EXPOSE 8080

CMD ["java", "-jar", "html2pdf.jar"]