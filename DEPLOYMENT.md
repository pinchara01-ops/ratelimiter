# RAT-28: Production Deployment Guide

This guide walks through deploying all three RateForge services to production.

## Local Development (Verified ✅)

```bash
# Start all services locally with Docker Compose
docker compose up -d

# Verify all services are healthy
docker compose ps
```

**Services:**
| Service | Port | Health |
|---------|------|--------|
| Redis | 6379 | ✅ Healthy |
| PostgreSQL | 5432 | ✅ Healthy |
| Server (gRPC) | 9090 | ✅ Healthy |
| Analytics (gRPC) | 50052 | ✅ Healthy |
| Dashboard | 3000 | ✅ Running |

---

## Cloud Deployment Options

### Option A: Railway.app (Recommended - Free Tier)

Railway offers a generous free tier without requiring a credit card.

```bash
# Install Railway CLI
npm install -g @railway/cli

# Login
railway login

# Initialize project
railway init

# Add PostgreSQL and Redis
railway add  # Select Database > PostgreSQL, Redis

# Deploy server
railway up

# Set environment variables in Railway dashboard
```

**Railway Project URL:** https://railway.com/project/68abac4e-e35d-4ea1-b620-d39ac87b6fa3

### Option B: Fly.io (Requires Credit Card)

## Prerequisites

✅ **Completed:** 
- [x] Fly CLI installed (`winget install Fly-io.flyctl`)
- [x] Vercel CLI installed (`npm install -g vercel`)
- [x] Applications built successfully (`./gradlew clean build -x test`)
- [x] Docker images build successfully
- [x] Local docker-compose stack verified working

🔐 **Required:**
- [ ] Authenticated with Fly.io (`flyctl auth login`)
- [ ] Authenticated with Vercel (`vercel login`)
- [ ] Fly.io billing configured (free tier available)
- [ ] Fly.io apps created or ready to create

---

## Deployment Steps

### 1. Deploy Server to Fly.io

```bash
# Navigate to project root
cd C:\Users\inchara P\ratelimiter

# Deploy server with fly.toml
flyctl deploy --config fly.toml

# If app doesn't exist, create it first:
flyctl apps create rateforge-server

# Set up managed PostgreSQL (recommended)
flyctl postgres create --name rateforge-db --region iad
flyctl postgres attach rateforge-db --app rateforge-server

# Set up managed Redis
flyctl redis create --name rateforge-redis --region iad
flyctl redis connect rateforge-redis --app rateforge-server

# Set required environment variables
flyctl secrets set \
  DB_HOST="[postgres-hostname]" \
  DB_NAME="rateforge" \
  DB_USER="[postgres-username]" \
  DB_PASSWORD="[postgres-password]" \
  REDIS_HOST="[redis-hostname]" \
  --app rateforge-server
```

**Expected outcome:** Server running at `https://rateforge-server.fly.dev`

### 2. Deploy Analytics Service to Fly.io

```bash
# Deploy analytics service with fly.analytics.toml
flyctl deploy --config fly.analytics.toml

# If app doesn't exist, create it first:
flyctl apps create rateforge-analytics

# Connect to same PostgreSQL as server
flyctl postgres attach rateforge-db --app rateforge-analytics

# Set environment variables
flyctl secrets set \
  DATABASE_URL="postgres://[user]:[password]@[host]:5432/rateforge" \
  DATABASE_USER="[postgres-username]" \
  DATABASE_PASSWORD="[postgres-password]" \
  --app rateforge-analytics
```

**Expected outcome:** Analytics service running at `https://rateforge-analytics.fly.dev`

### 3. Deploy Dashboard to Vercel

```bash
# Navigate to dashboard directory
cd dashboard-service

# Deploy to production
vercel deploy --prod

# Set environment variables in Vercel
vercel env add ANALYTICS_GRPC_URL "https://rateforge-analytics.fly.dev:50052" production
vercel env add CONFIG_GRPC_URL "https://rateforge-server.fly.dev:9090" production
vercel env add RATELIMIT_GRPC_URL "https://rateforge-server.fly.dev:9090" production

# Redeploy to apply env vars
vercel deploy --prod
```

**Expected outcome:** Dashboard running at `https://[project-name].vercel.app`

---

## Configuration Details

### fly.toml (Server Configuration)
- **App:** `rateforge-server`
- **Port:** 9090 (gRPC), 9091 (metrics)
- **Health checks:** TCP on port 9090
- **Metrics:** Exposed on `/metrics` (port 9091)

### fly.analytics.toml (Analytics Configuration)  
- **App:** `rateforge-analytics`
- **Port:** 50052 (gRPC)
- **Database:** Shared PostgreSQL with server

### vercel.json (Dashboard Configuration)
- **Framework:** Next.js
- **Build:** `cd dashboard-service && npm run build`
- **Environment variables:** gRPC service URLs

---

## Validation Checklist

### Automated Tests
```bash
# Run deployment verification
.\verify-deployment.bat [server-url] [analytics-url] [dashboard-url]

# Example:
.\verify-deployment.bat rateforge-server.fly.dev rateforge-analytics.fly.dev my-dashboard.vercel.app
```

### Manual Tests
- [ ] **Server Health:** https://rateforge-server.fly.dev/actuator/health returns `{"status":"UP"}`
- [ ] **Server Metrics:** https://rateforge-server.fly.dev/actuator/prometheus shows `rateforge_*` metrics
- [ ] **gRPC Connectivity:** Port 9090 reachable on server URL
- [ ] **Analytics gRPC:** Port 50052 reachable on analytics URL  
- [ ] **Dashboard Loads:** Vercel URL shows RateForge dashboard
- [ ] **Policy CRUD:** Can create/update/delete policies via dashboard
- [ ] **Live Feed:** Dashboard shows real-time decisions from deployed server
- [ ] **Analytics Charts:** Charts populate with data from deployed analytics service
- [ ] **Local Docker:** `docker-compose up` still works (no regressions)

---

## Environment Variables Summary

### Server (Fly.io)
```env
DB_HOST=rateforge-db.flycast
DB_PORT=5432
DB_NAME=rateforge  
DB_USER=[managed-postgres-user]
DB_PASSWORD=[managed-postgres-password]
REDIS_HOST=rateforge-redis.flycast
REDIS_PORT=6379
GRPC_PORT=9090
```

### Analytics (Fly.io)
```env
DATABASE_URL=postgres://[user]:[password]@[host]:5432/rateforge
DATABASE_USER=[postgres-username]
DATABASE_PASSWORD=[postgres-password]
GRPC_PORT=50052
```

### Dashboard (Vercel)
```env
ANALYTICS_GRPC_URL=https://rateforge-analytics.fly.dev:50052
CONFIG_GRPC_URL=https://rateforge-server.fly.dev:9090
RATELIMIT_GRPC_URL=https://rateforge-server.fly.dev:9090
```

---

## Monitoring

### Health Endpoints
- **Server:** `https://rateforge-server.fly.dev/actuator/health`
- **Metrics:** `https://rateforge-server.fly.dev/actuator/prometheus`

### Logs
```bash
# Server logs
flyctl logs --app rateforge-server

# Analytics logs  
flyctl logs --app rateforge-analytics

# Dashboard logs
vercel logs [deployment-url]
```

---

## Troubleshooting

### Common Issues
1. **Database Connection Failures**
   - Verify managed PostgreSQL is attached to both apps
   - Check `DATABASE_URL` environment variable format
   - Ensure both apps are in same region as database

2. **gRPC Connection Issues**
   - Verify ports 9090 and 50052 are exposed in fly.toml files
   - Check firewall/security group settings
   - Test with `grpcurl` if available

3. **Dashboard Environment Variables**
   - Ensure URLs use HTTPS scheme
   - Verify gRPC ports are included in URLs
   - Check Vercel environment variable deployment

### Recovery Commands
```bash
# Restart services
flyctl machine restart --app rateforge-server
flyctl machine restart --app rateforge-analytics

# Check app status
flyctl status --app rateforge-server
flyctl status --app rateforge-analytics
```