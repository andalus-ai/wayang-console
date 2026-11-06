# Wayang Console backend

Quarkus application. Domain and application services remain independent of Quarkus, HTTP, persistence, and Numou APIs.

## Requirements
- JDK 21+
- Maven 3.9+
- PostgreSQL for production-like integration testing

## Run
mvn -f backend/pom.xml quarkus:dev

The initial foundation exposes health and API metadata only. Runtime enrollment must not be enabled until one-time credential hashing, tenant/environment binding, authentication, authorization, rate limits, and audit persistence are implemented.

Package boundaries: domain, application, adapter, and bootstrap. Do not include workflow execution engines or the full Wayang component catalog.
