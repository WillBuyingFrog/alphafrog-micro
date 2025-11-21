# Portfolio Service Configuration Guide

## Overview

This document provides comprehensive configuration instructions for the Portfolio Service, including environment setup, configuration files, and startup procedures.

## Configuration Files

The Portfolio Service supports multiple configuration approaches:

### 1. Main Configuration Files

- **`application.yml`** - Primary YAML configuration (already complete)
- **`application.properties`** - Complete properties template with all available options
- **`application-dev.properties`** - Development environment specific settings
- **`application-local.properties`** - Local development settings

### 2. Environment Variables

Use the `env-template.properties` file as a guide for required environment variables.

## Quick Start

### Local Development

1. **Set up environment variables:**
   ```bash
   export AF_DB_MAIN_HOST=localhost
   export AF_DB_MAIN_PORT=5432
   export AF_DB_MAIN_DATABASE=alphafrog_local
   export AF_DB_MAIN_USER=postgres
   export AF_DB_MAIN_PASSWORD=your_password
   export AF_REDIS_HOST=127.0.0.1
   export AF_REDIS_PORT=6379
   export AF_REDIS_PASSWORD=
   export NACOS_ADDRESS=localhost
   export NACOS_PORT=8848
   ```

2. **Start the service:**
   ```bash
   # Using the startup script
   ./start-service.sh -p local
   
   # Or using Maven
   mvn spring-boot:run -Dspring.profiles.active=local
   
   # Or using Java directly
   java -jar target/portfolioService-*.jar --spring.profiles.active=local
   ```

### Development Environment

1. **Set up environment variables for dev environment:**
   ```bash
   export AF_DB_MAIN_HOST=dev-db.abc.com
   export AF_DB_MAIN_PORT=5432
   export AF_DB_MAIN_DATABASE=alphafrog_dev
   export AF_DB_MAIN_USER=dev_user
   export AF_DB_MAIN_PASSWORD=dev_password
   export AF_REDIS_HOST=dev-redis.abc.com
   export AF_REDIS_PORT=6379
   export AF_REDIS_PASSWORD=dev_redis_password
   export NACOS_ADDRESS=dev-nacos.abc.com
   export NACOS_PORT=8848
   ```

2. **Start the service:**
   ```bash
   ./start-service.sh -p dev
   ```

## Configuration Details

### Database Configuration

The service uses PostgreSQL with the following settings:

```properties
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.url=jdbc:postgresql://${AF_DB_MAIN_HOST}:${AF_DB_MAIN_PORT}/${AF_DB_MAIN_DATABASE}
spring.datasource.username=${AF_DB_MAIN_USER}
spring.datasource.password=${AF_DB_MAIN_PASSWORD}
```

**Required Environment Variables:**
- `AF_DB_MAIN_HOST` - Database host
- `AF_DB_MAIN_PORT` - Database port (default: 5432)
- `AF_DB_MAIN_DATABASE` - Database name
- `AF_DB_MAIN_USER` - Database username
- `AF_DB_MAIN_PASSWORD` - Database password

### Redis Configuration

Redis is used for caching:

```properties
spring.data.redis.host=${AF_REDIS_HOST:127.0.0.1}
spring.data.redis.port=6379
spring.data.redis.password=${AF_REDIS_PASSWORD:default}
```

**Environment Variables:**
- `AF_REDIS_HOST` - Redis host (default: 127.0.0.1)
- `AF_REDIS_PORT` - Redis port (default: 6379)
- `AF_REDIS_PASSWORD` - Redis password (optional)

### Dubbo Configuration

Dubbo RPC framework configuration:

```properties
dubbo.application.name=portfolio-service
dubbo.registry.address=nacos://${NACOS_ADDRESS:127.0.0.1}:8848
dubbo.protocol.name=tri
dubbo.protocol.port=50054
```

**Environment Variables:**
- `NACOS_ADDRESS` - Nacos server address (default: 127.0.0.1)
- `NACOS_PORT` - Nacos server port (default: 8848)

### JPA Configuration

JPA settings for data persistence:

```properties
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
```

### Service-Specific Configuration

Portfolio calculation settings:

```properties
portfolio.calculation.max-holding-age-days=365
portfolio.calculation.price-cache-ttl-minutes=5
portfolio.asset.default-currency=CNY
portfolio.asset.price-precision=4
```

## Profiles

### Local Profile (`application-local.properties`)
- Designed for local development
- Uses local database and services
- Verbose logging enabled
- Auto schema update enabled
- Development tools enabled

### Development Profile (`application-dev.properties`)
- Designed for shared development environment
- Uses development database and services
- Moderate logging
- Schema validation enabled
- Connects to development infrastructure

### Production Profile
- Create `application-prod.properties` for production use
- Use environment variables for sensitive data
- Minimal logging
- Schema validation only
- SSL/TLS enabled
- Connection pooling optimized

## Startup Script Usage

The `start-service.sh` script provides convenient startup options:

```bash
# Show help
./start-service.sh --help

# Start with local profile
./start-service.sh -p local

# Start with dev profile on custom port
./start-service.sh -p dev -P 8085

# Build and start
./start-service.sh -b -p local

# Check environment and dependencies only
./start-service.sh -c
```

## Environment Validation

The startup script automatically checks:

1. **Required environment variables** - Ensures all database credentials are set
2. **Service dependencies** - Checks if PostgreSQL, Redis, and Nacos are accessible
3. **Build artifacts** - Verifies JAR file exists or builds if necessary

## Troubleshooting

### Common Issues

1. **Database connection failed**
   - Check PostgreSQL is running
   - Verify environment variables are set correctly
   - Ensure database exists and user has permissions

2. **Redis connection failed**
   - Check Redis is running
   - Verify Redis host and port settings
   - Check Redis password if authentication is enabled

3. **Nacos registration failed**
   - Check Nacos is running
   - Verify Nacos address and port
   - Check network connectivity

4. **Service won't start**
   - Check environment variables are set
   - Review logs for specific errors
   - Ensure all dependencies are running

### Log Files

- Local development: Console output (no file logging)
- Development: `logs/portfolio-service-dev.log`
- Production: `logs/portfolio-service.log`

### Debug Logging

Enable debug logging by setting:
```properties
logging.level.world.willfrog.alphafrogmicro.portfolioservice=DEBUG
logging.level.org.springframework.web=DEBUG
logging.level.org.hibernate=DEBUG
```

## Security Considerations

1. **Never commit passwords** to version control
2. **Use strong passwords** for production databases
3. **Enable SSL/TLS** for database connections in production
4. **Use secrets management** tools for production deployments
5. **Secure Redis** with authentication in production
6. **Enable Nacos authentication** in production environments

## Next Steps

1. Set up your local development environment
2. Configure the required services (PostgreSQL, Redis, Nacos)
3. Start the service using the appropriate profile
4. Verify the service is running by checking health endpoint: `http://localhost:8084/actuator/health`
5. Test the service functionality using the provided APIs