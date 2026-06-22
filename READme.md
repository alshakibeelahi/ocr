## 1. Start Ollama in Docker and pull the model
cd D:\Code\Java\ocr
docker compose up -d

## Wait for the vision model to finish downloading (3.2 GB). Check progress:
docker logs -f ocr-ollama-init

## 2. Start the Spring Boot app
mvn spring-boot:run -s .mvn/settings.xml

## 3. Open http://localhost:8080 and upload a PDF
