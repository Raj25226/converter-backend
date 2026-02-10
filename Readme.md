# File Converter Backend - Extended Implementation

## Overview
This Spring Boot backend has been extended to match the frontend API specifications with the following features:

### New Features Added

1. **Google OAuth Authentication** (`/api/auth/google`)
2. **Additional File Conversion Endpoints** (7 new endpoints)
3. **Analytics System** (visit tracking, conversion tracking, stats)
4. **Enhanced Security & Error Handling**

---

## Project Structure

```
src/main/java/com/converter/
├── config/
│   ├── CorsConfig.java
│   ├── FileStorageConfig.java
│   └── SecurityConfig.java
├── controller/
│   ├── AnalyticsController.java
│   ├── AuthController.java
│   ├── ConversionController.java
│   └── FileController.java
├── dto/
│   ├── AnalyticsStatsResponse.java
│   ├── ApiResponse.java
│   ├── AuthRequest.java
│   ├── AuthResponse.java
│   ├── ConversionResponse.java
│   ├── GoogleAuthRequest.java
│   ├── RegisterRequest.java
│   ├── TrackConversionRequest.java
│   └── TrackVisitRequest.java
├── entity/
│   ├── Analytics.java
│   └── User.java
├── repository/
│   ├── AnalyticsRepository.java
│   └── UserRepository.java
├── service/
│   ├── AnalyticsService.java
│   ├── AuthService.java
│   ├── FileCleanupService.java
│   ├── FileConversionService.java
│   ├── FileService.java
│   └── GoogleAuthService.java
├── util/
│   └── JwtUtil.java
└── FileConverterApplication.java
```

---

## Installation & Setup

### Prerequisites
- Java 21+
- Maven 3.8+
- PostgreSQL 14+
- (Optional) Google Cloud Console account for OAuth

### 1. Database Setup

```sql
-- Create database
CREATE DATABASE converter;

-- The application will auto-create tables with the following schema:

-- Users table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    email_verified BOOLEAN NOT NULL DEFAULT false,
    role VARCHAR(20) NOT NULL DEFAULT 'USER'
);

-- Analytics table
CREATE TABLE analytics (
    id VARCHAR(36) PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    converter_type VARCHAR(50),
    ip_address VARCHAR(45),
    user_agent TEXT,
    timestamp TIMESTAMP NOT NULL
);
```

### 2. Environment Variables

Create a `.yml` file or set these environment variables:

```bash
# Database
DB_URL=jdbc:postgresql://localhost:5432/converter
DB_USERNAME=postgres
DB_PASSWORD=your_password

# JWT
JWT_SECRET=your_very_long_secret_key_at_least_32_characters_long
JWT_EXPIRATION=86400000

# Google OAuth (Optional)
GOOGLE_CLIENT_ID=your_google_client_id.apps.googleusercontent.com

# CORS
CORS_ALLOWED_ORIGINS=http://localhost:3000,https://yourdomain.com

# File Storage
FILE_UPLOAD_DIR=./uploads
FILE_CLEANUP_DELAY=1800000

# Server
SERVER_PORT=8080
```

### 3. Google OAuth Setup (Optional)

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing
3. Enable Google+ API
4. Create OAuth 2.0 credentials
5. Add authorized JavaScript origins: `http://localhost:3000`
6. Add authorized redirect URIs: `http://localhost:3000`
7. Copy the Client ID and set it in `GOOGLE_CLIENT_ID`

### 4. Build & Run

```bash
# Install dependencies
mvn clean install

# Run the application
mvn spring-boot:run

# Or build JAR and run
mvn package
java -jar target/converter-0.0.1-SNAPSHOT.jar
```

---

## API Endpoints

### Authentication Endpoints

#### 1. Register
```http
POST /api/auth/register
Content-Type: application/json

{
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "password": "password123"
}
```

#### 2. Login
```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "john@example.com",
  "password": "password123"
}
```

#### 3. Google OAuth
```http
POST /api/auth/google
Content-Type: application/json

{
  "credential": "google_id_token_here"
}
```

#### 4. Validate Token
```http
GET /api/auth/validate
Authorization: Bearer <token>
```

#### 5. Get Current User
```http
GET /api/auth/me
Authorization: Bearer <token>
```

---

### File Conversion Endpoints

All conversion endpoints follow the same pattern:

```http
POST /api/convert/{conversion-type}
Content-Type: multipart/form-data

file: <binary-file-data>
```

**Available Conversions:**

| Endpoint | Input Formats | Output Format |
|----------|--------------|---------------|
| `/pdf-to-word` | .pdf | .docx |
| `/word-to-pdf` | .docx, .doc | .pdf |
| `/jpg-to-png` | .jpg, .jpeg | .png |
| `/png-to-jpg` | .png | .jpg |
| `/excel-to-pdf` | .xlsx, .xls | .pdf |
| `/pdf-to-excel` | .pdf | .xlsx |
| `/mp4-to-mp3` | .mp4, .mov, .avi | .mp3 |
| `/ppt-to-pdf` | .pptx, .ppt | .pdf |
| `/heic-to-jpg` | .heic, .heif | .jpg |
| `/webp-to-png` | .webp | .png |

**Response:**
```json
{
  "status": "success",
  "fileId": "abc123.docx",
  "fileName": "abc123.docx",
  "downloadUrl": "/api/download/abc123.docx",
  "fileSize": 12345
}
```

---

### Analytics Endpoints

#### 1. Get Statistics
```http
GET /api/analytics/stats

Response:
{
  "status": "success",
  "data": {
    "totalVisits": 125430,
    "totalConversions": 45678,
    "uniqueVisitors": 89234,
    "conversionRate": 36.4
  }
}
```

#### 2. Track Visit
```http
POST /api/analytics/track-visit
Content-Type: application/json

{
  "page": "/",
  "timestamp": "2026-02-10T10:30:00.000Z"
}
```

#### 3. Track Conversion
```http
POST /api/analytics/track-conversion
Content-Type: application/json

{
  "converterType": "pdf-to-word",
  "timestamp": "2026-02-10T10:35:00.000Z"
}
```

---

### File Download Endpoint

```http
GET /api/download/{fileId}

Response: Binary file data with appropriate headers
```

---

## Implementation Notes

### File Conversions

#### Fully Implemented:
- ✅ PDF to Word (using Apache PDFBox)
- ✅ JPG to PNG (using Java ImageIO)
- ✅ PNG to JPG (using Java ImageIO)
- ✅ WebP to PNG (using Java ImageIO with plugins)

#### Placeholder Implementations (Require Additional Setup):

**Word to PDF, Excel to PDF, PowerPoint to PDF:**
- Requires: LibreOffice + Documents4j library
- Alternative: Commercial APIs (Aspose, GroupDocs)
- Current: Placeholder that copies file

**PDF to Excel:**
- Requires: Tabula library for table extraction
- Alternative: Apache PDFBox + custom table detection
- Current: Placeholder

**MP4 to MP3:**
- Requires: FFmpeg installed on server
- Alternative: JAVE library
- Current: Placeholder

**HEIC to JPG:**
- Requires: imageio-heif library
- Alternative: ImageMagick command-line tool
- Current: Placeholder

### Production Setup Recommendations

#### 1. Install FFmpeg (for video conversions)
```bash
# Ubuntu/Debian
sudo apt-get install ffmpeg

# macOS
brew install ffmpeg
```

#### 2. Install LibreOffice (for document conversions)
```bash
# Ubuntu/Debian
sudo apt-get install libreoffice

# macOS
brew install --cask libreoffice
```

#### 3. Add Documents4j to pom.xml
```xml
<dependency>
    <groupId>com.documents4j</groupId>
    <artifactId>documents4j-local</artifactId>
    <version>1.1.11</version>
</dependency>
<dependency>
    <groupId>com.documents4j</groupId>
    <artifactId>documents4j-transformer-msoffice-word</artifactId>
    <version>1.1.11</version>
</dependency>
```

#### 4. Enhanced Security

```java
// Add rate limiting
@Configuration
public class RateLimitConfig {
    @Bean
    public RateLimiter conversionRateLimiter() {
        return RateLimiter.create(100.0 / 3600.0); // 100 per hour
    }
}

// Add file validation
public void validateFile(MultipartFile file) {
    // Check file size
    if (file.getSize() > 50 * 1024 * 1024) {
        throw new IllegalArgumentException("File too large");
    }
    
    // Check MIME type
    String contentType = file.getContentType();
    if (!ALLOWED_TYPES.contains(contentType)) {
        throw new IllegalArgumentException("Invalid file type");
    }
    
    // Scan for malware (integrate with ClamAV)
}
```

---

## Testing

### cURL Examples

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"firstName":"John","lastName":"Doe","email":"john@test.com","password":"password123"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"john@test.com","password":"password123"}'

# Convert file
curl -X POST http://localhost:8080/api/convert/pdf-to-word \
  -F "file=@document.pdf" \
  -o response.json

# Get analytics
curl http://localhost:8080/api/analytics/stats

# Track visit
curl -X POST http://localhost:8080/api/analytics/track-visit \
  -H "Content-Type: application/json" \
  -d '{"page":"/","timestamp":"2026-02-10T10:30:00.000Z"}'
```

### Integration Tests

Create tests in `src/test/java`:

```java
@SpringBootTest
@AutoConfigureMockMvc
class ConversionControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testPdfToWordConversion() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "test.pdf",
            "application/pdf",
            "test content".getBytes()
        );
        
        mockMvc.perform(multipart("/api/convert/pdf-to-word")
                .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }
}
```

---

## Deployment

### Docker

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/converter-0.0.1-SNAPSHOT.jar app.jar

# Install FFmpeg and LibreOffice
RUN apt-get update && \
    apt-get install -y ffmpeg libreoffice && \
    rm -rf /var/lib/apt/lists/*

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Docker Compose

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: converter
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  backend:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/converter
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      JWT_SECRET: your_secret_key_here
    depends_on:
      - postgres
    volumes:
      - ./uploads:/app/uploads

volumes:
  postgres_data:
```

---

## Monitoring & Logging

### Add Actuator

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  metrics:
    export:
      prometheus:
        enabled: true
```

### Log Aggregation

```yaml
logging:
  file:
    name: logs/application.log
  pattern:
    file: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
```

---

## Security Checklist

- [x] HTTPS in production
- [x] CORS properly configured
- [x] JWT tokens with expiration
- [x] Password hashing with BCrypt
- [x] SQL injection prevention (JPA)
- [x] Path traversal protection
- [ ] Rate limiting (implement with Bucket4j)
- [ ] File malware scanning (integrate ClamAV)
- [ ] API key authentication for sensitive endpoints
- [ ] CSRF protection for state-changing operations

---

## Performance Optimization

1. **File Storage**: Use S3 or MinIO for production
2. **Caching**: Implement Redis for analytics caching
3. **Queue System**: Use RabbitMQ or Kafka for async conversions
4. **Database Indexing**: Add indexes on frequently queried columns
5. **Connection Pooling**: Configure HikariCP properly

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

---

## Troubleshooting

### Common Issues

**1. Google OAuth not working:**
- Verify `GOOGLE_CLIENT_ID` is set correctly
- Check authorized origins in Google Console
- Ensure frontend sends valid credential token

**2. File conversions failing:**
- Check server has required tools installed (FFmpeg, LibreOffice)
- Verify file upload directory exists and is writable
- Check file size limits in application.yaml

**3. Database connection errors:**
- Verify PostgreSQL is running
- Check database credentials
- Ensure database exists

**4. CORS errors:**
- Update `CORS_ALLOWED_ORIGINS` to include your frontend URL
- Restart backend after changing configuration

---

## Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open Pull Request

---

## License

This project is licensed under the MIT License.

---

## Support

For issues and questions:
- GitHub Issues: (https://github.com/Raj25226/converter-backend)
- Email: support@example.com
