#!/bin/bash

# Build script for Transactional Outbox Library

set -e

echo "🚀 Building Transactional Outbox Library..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    print_error "Maven is not installed. Please install Maven first."
    exit 1
fi

# Check if Java 17+ is installed
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    print_error "Java 17 or higher is required. Current version: $JAVA_VERSION"
    exit 1
fi

print_status "Java version: $(java -version 2>&1 | head -n 1)"

# Clean previous build
print_status "Cleaning previous build..."
mvn clean

# Compile
print_status "Compiling..."
mvn compile

# Run tests
print_status "Running tests..."
mvn test

# Package
print_status "Packaging..."
mvn package -DskipTests

print_status "Build completed successfully! 🎉"
print_status "JAR file location: target/transactional-outbox-library-1.0.0.jar"

# Optional: Run integration tests
if [ "$1" = "--integration" ]; then
    print_status "Running integration tests..."
    mvn verify
fi

# Optional: Install to local repository
if [ "$1" = "--install" ]; then
    print_status "Installing to local Maven repository..."
    mvn install -DskipTests
fi

print_status "Build script completed!" 