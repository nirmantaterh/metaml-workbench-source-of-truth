# RedCollarTP

Camunda 7 + Spring Boot process engine, structured around a **proxy / twin** pattern:
- `proxy` — outward-facing process delegates that receive commands
- `twin` — internal digital-twin mirror kept in sync via RabbitMQ

## Scaffold notes
This structure was rebuilt from a VS Code screenshot. The `proxy/delegates` package is
reproduced in full (matches the visible file tree). `config`, `controller`, RabbitMQ
config, and `twin` package content were not visible in the screenshot, so they're
filled in as reasonable placeholders — replace with your real logic.

Run: `./mvnw spring-boot:run`
