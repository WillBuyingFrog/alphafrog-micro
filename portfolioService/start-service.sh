#!/bin/bash

# Portfolio Service Startup Script
# ===============================================
# This script helps start the portfolio service with proper configuration

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default configuration
DEFAULT_PROFILE="local"
DEFAULT_PORT="8084"

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to check if required environment variables are set
check_environment() {
    local required_vars=(
        "AF_DB_MAIN_HOST"
        "AF_DB_MAIN_PORT"
        "AF_DB_MAIN_DATABASE"
        "AF_DB_MAIN_USER"
        "AF_DB_MAIN_PASSWORD"
    )
    
    local missing_vars=()
    
    for var in "${required_vars[@]}"; do
        if [ -z "${!var}" ]; then
            missing_vars+=("$var")
        fi
    done
    
    if [ ${#missing_vars[@]} -ne 0 ]; then
        print_error "Missing required environment variables:"
        for var in "${missing_vars[@]}"; do
            echo "  - $var"
        done
        echo ""
        print_warning "Please set the required environment variables."
        echo "You can:"
        echo "1. Set them in your shell environment"
        echo "2. Create a .env file and source it"
        echo "3. Use the env-template.properties file as a guide"
        return 1
    fi
    
    return 0
}

# Function to check if required services are running
check_dependencies() {
    print_info "Checking service dependencies..."
    
    # Check PostgreSQL
    if command_exists pg_isready; then
        if ! pg_isready -h "${AF_DB_MAIN_HOST:-localhost}" -p "${AF_DB_MAIN_PORT:-5432}" >/dev/null 2>&1; then
            print_warning "PostgreSQL may not be running on ${AF_DB_MAIN_HOST:-localhost}:${AF_DB_MAIN_PORT:-5432}"
        else
            print_info "PostgreSQL is running"
        fi
    else
        print_warning "pg_isready not found, skipping PostgreSQL check"
    fi
    
    # Check Redis
    if command_exists redis-cli; then
        if ! redis-cli -h "${AF_REDIS_HOST:-127.0.0.1}" -p "${AF_REDIS_PORT:-6379}" ping >/dev/null 2>&1; then
            print_warning "Redis may not be running on ${AF_REDIS_HOST:-127.0.0.1}:${AF_REDIS_PORT:-6379}"
        else
            print_info "Redis is running"
        fi
    else
        print_warning "redis-cli not found, skipping Redis check"
    fi
    
    # Check Nacos
    if command_exists curl; then
        local nacos_host="${NACOS_ADDRESS:-127.0.0.1}"
        local nacos_port="${NACOS_PORT:-8848}"
        if ! curl -s "http://${nacos_host}:${nacos_port}/nacos/v1/console/health/liveness" >/dev/null 2>&1; then
            print_warning "Nacos may not be running on ${nacos_host}:${nacos_port}"
        else
            print_info "Nacos is running"
        fi
    else
        print_warning "curl not found, skipping Nacos check"
    fi
}

# Function to build the project
build_project() {
    print_info "Building portfolio service..."
    
    if ! ./mvnw clean package -DskipTests; then
        print_error "Build failed!"
        return 1
    fi
    
    print_info "Build successful!"
    return 0
}

# Function to start the service
start_service() {
    local profile="${1:-$DEFAULT_PROFILE}"
    local port="${2:-$DEFAULT_PORT}"
    
    print_info "Starting portfolio service with profile: $profile, port: $port"
    
    # Set JVM options
    export JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication"
    
    # Set Spring profile
    export SPRING_PROFILES_ACTIVE="$profile"
    
    # Start the service
    java $JAVA_OPTS \
        -Dspring.profiles.active="$profile" \
        -Dserver.port="$port" \
        -jar target/portfolioService-*.jar
}

# Function to show usage
show_usage() {
    echo "Portfolio Service Startup Script"
    echo ""
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -p, --profile PROFILE    Spring profile (dev, local, prod) [default: local]"
    echo "  -P, --port PORT          Server port [default: 8084]"
    echo "  -b, --build              Build the project before starting"
    echo "  -c, --check              Check environment and dependencies only"
    echo "  -h, --help               Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                       # Start with local profile on default port"
    echo "  $0 -p dev -P 8085        # Start with dev profile on port 8085"
    echo "  $0 -b -p local           # Build and start with local profile"
    echo "  $0 -c                    # Check environment and dependencies"
    echo ""
    echo "Environment Variables Required:"
    echo "  AF_DB_MAIN_HOST          Database host"
    echo "  AF_DB_MAIN_PORT          Database port"
    echo "  AF_DB_MAIN_DATABASE      Database name"
    echo "  AF_DB_MAIN_USER          Database username"
    echo "  AF_DB_MAIN_PASSWORD      Database password"
    echo "  AF_REDIS_HOST            Redis host (optional, default: 127.0.0.1)"
    echo "  AF_REDIS_PORT            Redis port (optional, default: 6379)"
    echo "  AF_REDIS_PASSWORD        Redis password (optional)"
    echo "  NACOS_ADDRESS            Nacos address (optional, default: 127.0.0.1)"
    echo "  NACOS_PORT               Nacos port (optional, default: 8848)"
}

# Main function
main() {
    local profile="$DEFAULT_PROFILE"
    local port="$DEFAULT_PORT"
    local build=false
    local check_only=false
    
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            -p|--profile)
                profile="$2"
                shift 2
                ;;
            -P|--port)
                port="$2"
                shift 2
                ;;
            -b|--build)
                build=true
                shift
                ;;
            -c|--check)
                check_only=true
                shift
                ;;
            -h|--help)
                show_usage
                exit 0
                ;;
            *)
                print_error "Unknown option: $1"
                show_usage
                exit 1
                ;;
        esac
    done
    
    # Check if Maven wrapper exists
    if [ ! -f "./mvnw" ]; then
        print_error "Maven wrapper not found! Please run 'mvn clean package' manually."
        exit 1
    fi
    
    # Check environment
    if ! check_environment; then
        exit 1
    fi
    
    # Check dependencies
    check_dependencies
    
    # If check only, exit here
    if [ "$check_only" = true ]; then
        exit 0
    fi
    
    # Build if requested
    if [ "$build" = true ]; then
        if ! build_project; then
            exit 1
        fi
    fi
    
    # Check if jar file exists
    if [ ! -f "target/portfolioService-"*.jar ]; then
        print_error "JAR file not found! Building the project..."
        if ! build_project; then
            exit 1
        fi
    fi
    
    # Start the service
    start_service "$profile" "$port"
}

# Run main function
main "$@"