# 🚀 Production File Converter - Complete Setup Guide

## ✅ What's Included

### Fully Working Features

1. **✅ Google OAuth 2.0** - Production-ready authentication
2. **✅ PDF ↔ Word** - Full text extraction and rendering
3. **✅ PDF ↔ Excel** - Table detection and spreadsheet creation
4. **✅ Image Conversions** - JPG, PNG, WebP with proper color handling
5. **✅ PowerPoint → PDF** - Slide rendering to PDF
6. **✅ Video → Audio** - MP4 to MP3 (requires FFmpeg)
7. **✅ HEIC → JPG** - Apple image format (requires ImageMagick)
8. **✅ Analytics System** - Automatic tracking with async processing
9. **✅ File Cleanup** - Auto-delete after 30 minutes
10. **✅ Docker Support** - Complete containerized deployment

---

## 📦 Quick Start (5 Minutes)

### Option 1: Docker (Recommended)

```bash
# 1. Clone and navigate to project
cd your-project

# 2. Copy environment template
cp .env.example .env

# 3. Edit .env and set:
#    - GOOGLE_CLIENT_ID (from Google Cloud Console)
#    - JWT_SECRET (generate with: openssl rand -base64 32)
#    - DB_PASSWORD

# 4. Start everything
docker-compose up -d

# 5. Check status
docker-compose ps

# 6. View logs
docker-compose logs -f backend
```

**Access:**
- Backend API: http://localhost:8080
- API Docs: http://localhost:8080/actuator
- Health Check: http://localhost:8080/actuator/health

### Option 2: Local Development

```bash
# 1. Install dependencies (Ubuntu/Debian)
sudo apt-get update
sudo apt-get install -y ffmpeg imagemagick libheif-examples

# 2. Setup database
docker run -d \
  --name converter-postgres \
  -e POSTGRES_DB=converter \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16

# 3. Configure environment
export GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
export JWT_SECRET="your-secret-key-here"

# 4. Build and run
mvn clean install
mvn spring-boot:run
```

---

## 🔐 Google OAuth Setup (10 Minutes)

### Step 1: Create Google Cloud Project

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Click **Select a project** → **New Project**
3. Name: "File Converter" → **Create**

### Step 2: Enable API

1. Go to **APIs & Services** → **Library**
2. Search "**Google+ API**"
3. Click **Enable**

### Step 3: Create OAuth Credentials

1. Go to **APIs & Services** → **Credentials**
2. Click **Create Credentials** → **OAuth 2.0 Client ID**

3. **Configure Consent Screen:**
   ```
   User Type: External
   App name: File Converter
   User support email: your-email@example.com
   Developer contact: your-email@example.com
   ```
   Click **Save and Continue** through all steps

4. **Create OAuth Client:**
   ```
   Application type: Web application
   Name: File Converter Web Client
   
   Authorized JavaScript origins:
   - http://localhost:3000
   - https://yourdomain.com
   
   Authorized redirect URIs:
   - http://localhost:3000
   - https://yourdomain.com
   ```

5. **Copy Client ID** and add to `.env`:
   ```bash
   GOOGLE_CLIENT_ID=123456789-abc123.apps.googleusercontent.com
   ```

### Step 4: Frontend Integration

Install Google OAuth library:
```bash
npm install @react-oauth/google
```

Wrap your app:
```javascript
import { GoogleOAuthProvider } from '@react-oauth/google';

function App() {
  return (
    <GoogleOAuthProvider clientId="YOUR_CLIENT_ID">
      {/* Your app */}
    </GoogleOAuthProvider>
  );
}
```

Add login button:
```javascript
import { GoogleLogin } from '@react-oauth/google';

function Login() {
  return (
    <GoogleLogin
      onSuccess={async (response) => {
        const res = await fetch('/api/auth/google', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ credential: response.credential })
        });
        
        const data = await res.json();
        localStorage.setItem('token', data.token);
        // Redirect to dashboard
      }}
      onError={() => console.log('Login Failed')}
    />
  );
}
```

---

## 🛠️ System Dependencies

### Ubuntu/Debian

```bash
sudo apt-get update
sudo apt-get install -y \
    ffmpeg \
    imagemagick \
    libheif-examples
```

### macOS

```bash
brew install ffmpeg imagemagick libheif
```

### Verify Installation

```bash
ffmpeg -version
convert --version
```

---

## 🧪 Testing

### Run Test Script

```bash
# Make script executable
chmod +x test-conversions.sh

# Run all tests
./test-conversions.sh
```

### Manual Testing

```bash
# Test Google OAuth
curl -X POST http://localhost:8080/api/auth/google \
  -H "Content-Type: application/json" \
  -d '{"credential":"YOUR_GOOGLE_TOKEN"}'

# Test PDF to Word
curl -X POST http://localhost:8080/api/convert/pdf-to-word \
  -F "file=@test.pdf" \
  | jq

# Test image conversion
curl -X POST http://localhost:8080/api/convert/jpg-to-png \
  -F "file=@test.jpg" \
  | jq

# Test video to audio
curl -X POST http://localhost:8080/api/convert/mp4-to-mp3 \
  -F "file=@test.mp4" \
  | jq

# Check analytics
curl http://localhost:8080/api/analytics/stats | jq
```

---

## 📁 File Structure

```
your-project/
├── src/
│   └── main/
│       ├── java/com/converter/
│       │   ├── controller/
│       │   │   ├── AuthController.java
│       │   │   ├── ConversionController.java
│       │   │   └── AnalyticsController.java
│       │   ├── service/
│       │   │   ├── GoogleAuthService.java ← Production Google OAuth
│       │   │   ├── FileConversionService.java ← All conversions working
│       │   │   └── AnalyticsService.java
│       │   └── ...
│       └── resources/
│           └── application.yaml
├── Dockerfile ← Production-ready with all dependencies
├── docker-compose.yml ← Complete stack
├── .env.example ← Environment template
├── test-conversions.sh ← Automated tests
└── pom.xml
```

---

## 🔄 Conversion Status

| Conversion | Status | Requirements | Notes |
|------------|--------|--------------|-------|
| PDF → Word | ✅ Working | None | Text extraction |
| Word → PDF | ✅ Working | None | Text rendering |
| JPG ↔ PNG | ✅ Working | None | Color space handling |
| Excel → PDF | ✅ Working | None | Table rendering |
| PDF → Excel | ✅ Working | None | Table detection |
| PPT → PDF | ✅ Working | None | Slide rendering |
| WebP → PNG | ✅ Working | None | Standard ImageIO |
| MP4 → MP3 | ✅ Working | FFmpeg | Audio extraction |
| HEIC → JPG | ✅ Working | ImageMagick | Image format |

---

## 🚀 Production Deployment

### Docker Production Stack

```bash
# Build production image
docker build -t file-converter:latest .

# Run with docker-compose
docker-compose --profile production up -d

# Scale backend
docker-compose up -d --scale backend=3
```

### Environment Variables for Production

```bash
# .env for production
DB_PASSWORD=your_secure_password
JWT_SECRET=$(openssl rand -base64 32)
GOOGLE_CLIENT_ID=your-production-client-id
CORS_ALLOWED_ORIGINS=https://yourdomain.com
JAVA_MAX_MEMORY=2g
```

### Nginx Reverse Proxy

Create `nginx.conf`:
```nginx
http {
    upstream backend {
        server backend:8080;
    }

    server {
        listen 80;
        server_name yourdomain.com;

        client_max_body_size 100M;

        location /api {
            proxy_pass http://backend;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }

        location / {
            root /usr/share/nginx/html;
            try_files $uri /index.html;
        }
    }
}
```

---

## 📊 Monitoring

### Health Checks

```bash
# Application health
curl http://localhost:8080/actuator/health

# FFmpeg availability
curl http://localhost:8080/health/ffmpeg

# Database connection
curl http://localhost:8080/actuator/health/db
```

### Logs

```bash
# Docker logs
docker-compose logs -f backend

# Application logs
tail -f logs/application.log

# Error logs
tail -f logs/error.log
```

### Metrics

Add to `pom.xml` for Prometheus metrics:
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Access metrics: http://localhost:8080/actuator/prometheus

---

## 🔒 Security Checklist

- [x] JWT with strong secret (32+ characters)
- [x] Password hashing with BCrypt
- [x] CORS properly configured
- [x] File type validation
- [x] File size limits (50MB)
- [x] Path traversal protection
- [x] Google OAuth with token verification
- [ ] Rate limiting (implement with Bucket4j)
- [ ] Malware scanning (integrate ClamAV)
- [ ] HTTPS in production
- [ ] API key for sensitive endpoints

---

## 🐛 Troubleshooting

### Google OAuth Not Working

```bash
# Check if client ID is set
echo $GOOGLE_CLIENT_ID

# Test with invalid token
curl -X POST http://localhost:8080/api/auth/google \
  -H "Content-Type: application/json" \
  -d '{"credential":"invalid"}' \
  | jq

# Should return: {"status":"error","message":"Invalid Google credential"}
```

### FFmpeg Not Found

```bash
# Check FFmpeg
which ffmpeg

# Install if missing
sudo apt-get install ffmpeg

# Test conversion
curl -X POST http://localhost:8080/api/convert/mp4-to-mp3 \
  -F "file=@test.mp4"
```

### ImageMagick PDF Blocked

```bash
# Edit policy
sudo nano /etc/ImageMagick-6/policy.xml

# Change:
<policy domain="coder" rights="none" pattern="PDF" />
# To:
<policy domain="coder" rights="read|write" pattern="PDF" />
```

### Database Connection Failed

```bash
# Check PostgreSQL
docker ps | grep postgres

# Check logs
docker logs converter-postgres

# Test connection
psql -h localhost -U postgres -d converter
```

---

## 📈 Performance Tips

### 1. Increase Memory

```yaml
# docker-compose.yml
environment:
  JAVA_OPTS: -Xmx2g -Xms1g
```

### 2. Enable Connection Pooling

```yaml
# application.yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
```

### 3. Add Redis Caching

```yaml
# docker-compose.yml
services:
  redis:
    image: redis:alpine
    ports:
      - "6379:6379"
```

---

## 📞 Support

### Common Issues

1. **Conversion fails:** Check system dependencies installed
2. **Google OAuth error:** Verify client ID and origins
3. **File too large:** Increase `max-file-size` in application.yaml
4. **Slow conversions:** Increase Java memory allocation

### Getting Help

- Check logs: `docker-compose logs backend`
- Test endpoints: Use `test-conversions.sh`
- Verify setup: Run health checks

---

## ✨ What's New

### Production-Ready Google OAuth
- ✅ Full token verification
- ✅ Email verification check
- ✅ User auto-creation
- ✅ Proper error handling

### Working File Conversions
- ✅ PDF ↔ Word with formatting
- ✅ PDF ↔ Excel with tables
- ✅ Images with color space handling
- ✅ PowerPoint → PDF with rendering
- ✅ Video → Audio with FFmpeg
- ✅ HEIC support with ImageMagick

### Auto Analytics
- ✅ Conversions tracked automatically
- ✅ Async processing (no slowdown)
- ✅ Stats cached (5 min)
- ✅ IP and user agent tracking

---

## 🎉 You're Ready!

Your file converter is now production-ready with:
- ✅ Working Google OAuth 2.0
- ✅ All conversions functioning
- ✅ Docker deployment ready
- ✅ Automated testing
- ✅ Analytics tracking
- ✅ Security best practices

**Start the application:**
```bash
docker-compose up -d
```

**Access your API:**
http://localhost:8080

**Happy converting! 🚀**