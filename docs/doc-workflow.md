# Spring Boot Monitoring Application Workflow

## Architecture Overview

Your application implements a complete observability stack with the following components:

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Grafana       │    │   Prometheus    │    │     Tempo       │
│   Port: 3000    │◄───┤   Port: 9090    │◄───┤   Port: 3200    │
│   (Dashboard)   │    │   (Metrics)     │    │   (Traces)      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         ▲                       ▲                       ▲
         │                       │                       │
         │               ┌───────┴───────┐               │
         │               │               │               │
         ▼               ▼               │               │
┌─────────────────┐    ┌─────────────────┐               │
│     Loki        │    │ Spring Boot App │               │
│   Port: 3100    │◄───┤   Port: 8081    │───────────────┘
│   (Logs)        │    │ (Your Service)  │
└─────────────────┘    └─────────────────┘
```

## Detailed Component Communication

### 1. Spring Boot Application (Port 8081)
**Role**: Your main product service that generates telemetry data

**Generates**:
- **Metrics**: HTTP request metrics, JVM metrics, custom business metrics
- **Traces**: Distributed tracing spans for request flows
- **Logs**: Application logs with correlation IDs

**Endpoints**:
- `/actuator/prometheus` - Metrics endpoint scraped by Prometheus
- `/actuator/health` - Health check endpoint
- Business endpoints (your REST APIs)

**Key Dependencies**:
- `micrometer-registry-prometheus` - Exports metrics in Prometheus format
- `micrometer-tracing-bridge-brave` - Distributed tracing support
- `zipkin-reporter-brave` - Sends traces to Tempo via Zipkin protocol
- `loki-logback-appender` - Sends logs to Loki

### 2. Prometheus (Port 9090)
**Role**: Metrics collection and storage

**Data Flow**:
- **Input**: Scrapes metrics from Spring Boot app every 5 seconds via HTTP GET to `http://my-product-service:8081/actuator/prometheus`
- **Storage**: Time-series database for metrics
- **Output**: Provides metrics data to Grafana via HTTP API

**Configuration**:
```yaml
scrape_configs:
  - job_name: 'product_service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 5s
    static_configs:
      - targets: ['my-product-service:8081']
```

### 3. Tempo (Port 3200, 9411) - The Zipkin Protocol Connection
**Role**: Distributed tracing backend that speaks Zipkin protocol

**Understanding Zipkin's Role**:
Zipkin is NOT running as a separate service in your setup, but its **protocol** is crucial:

**What is Zipkin?**
- Zipkin is a distributed tracing system originally created by Twitter
- It defined a standard protocol and data format for collecting and transmitting trace data
- The "Zipkin protocol" became an industry standard for trace ingestion

**How Zipkin Protocol Works in Your Setup**:

1. **Spring Boot Side (Trace Generation)**:
   ```java
   // Your dependencies create this flow:
   micrometer-tracing-bridge-brave → zipkin-reporter-brave → HTTP POST to Zipkin endpoint
   ```
    - `brave` library creates spans following Zipkin's span model
    - `zipkin-reporter-brave` formats traces in Zipkin JSON/Protobuf format
    - Sends HTTP POST requests to `http://my-tempo:9411/api/v2/spans`

2. **Tempo Side (Zipkin-Compatible Receiver)**:
    - Tempo exposes port **9411** as a Zipkin-compatible ingestion endpoint
    - Receives traces in Zipkin format (JSON or Protobuf)
    - Converts Zipkin spans to its internal format
    - This is why you see `9411` - it's the standard Zipkin port!

**Ports Explained**:
- **3200**: Tempo's native HTTP API (for Grafana queries using Tempo's own protocol)
- **9411**: Zipkin-compatible ingestion endpoint (receives traces from your app)

**The Zipkin Span Model**:
Your traces follow Zipkin's span structure:
```json
{
  "traceId": "1234567890abcdef",
  "spanId": "abcdef1234567890",
  "parentId": "fedcba0987654321",
  "name": "HTTP GET /api/products",
  "timestamp": 1640995200000000,
  "duration": 150000,
  "localEndpoint": {"serviceName": "my-product-service"},
  "tags": {"http.method": "GET", "http.url": "/api/products"}
}
```

**Why Use Zipkin Protocol Instead of Direct Tempo?**
- **Industry Standard**: Zipkin protocol is widely supported
- **Spring Boot Integration**: Spring's tracing libraries have mature Zipkin support
- **Compatibility**: Many tracing backends support Zipkin ingestion (Jaeger, Tempo, etc.)
- **Migration Path**: Easy to switch between different tracing backends

**Data Flow**:
- **Input**: Receives traces from Spring Boot app via Zipkin protocol on port 9411
- **Processing**:
    - Converts Zipkin spans to Tempo's internal format
    - Stores traces in local backend
    - Generates metrics from spans and sends to Prometheus
    - Creates service graphs and span metrics
- **Output**: Serves trace data to Grafana via Tempo's native API on port 3200

**Key Features**:
- Acts as a Zipkin-compatible receiver (port 9411)
- Metrics generator creates RED metrics (Rate, Errors, Duration) from traces
- Service graph processor builds service topology from Zipkin span relationships
- Sends exemplars back to Prometheus for trace-to-metrics correlation

### 4. Loki (Port 3100)
**Role**: Log aggregation and storage

**Data Flow**:
- **Input**: Receives structured logs from Spring Boot app via Loki logback appender
- **Storage**: Stores logs with labels and indexes
- **Output**: Provides log data to Grafana for visualization and correlation

**Log Format**: Your logs include correlation pattern `[${spring.application.name:},%X{traceId:-},%X{spanId:-}]` which enables trace-to-log correlation.

### 5. Grafana (Port 3000)
**Role**: Unified observability dashboard

**Data Sources**:
- **Prometheus** (`http://my-prometheus:9090`) - For metrics and alerts
- **Tempo** (`http://my-tempo:3200`) - For distributed traces
- **Loki** (`http://my-loki:3100`) - For logs

**Correlations Configured**:
- **Metrics → Traces**: Prometheus exemplars link to Tempo traces
- **Traces → Logs**: Tempo traces link to Loki logs via trace ID
- **Logs → Traces**: Loki derived fields extract trace IDs to link back to Tempo

## Request Flow Example

When a user makes an HTTP request to your Spring Boot application:

### 1. Request Processing (Port 8081)
```
HTTP Request → Spring Boot Controller
├── Trace Started (Span ID generated)
├── Database Query (if applicable)
├── Business Logic Processing
└── Response Returned
```

### 2. Telemetry Generation
**Metrics** (every 5 seconds):
```
Spring Boot App:8081/actuator/prometheus ← Prometheus:9090
```

**Traces** (real-time):
```
Spring Boot App → Tempo:9411 (Zipkin endpoint)
```

**Logs** (real-time):
```
Spring Boot App → Loki:3100 (with trace correlation)
```

### 3. Data Processing
**Tempo Processing**:
```
Tempo receives trace → Generates metrics → Sends to Prometheus:9090
```

### 4. Visualization
**Grafana Dashboard**:
```
Grafana:3000 queries:
├── Prometheus:9090 (for metrics charts)
├── Tempo:3200 (for trace visualization)
└── Loki:3100 (for log exploration)
```

## Key Configuration Details

### Tracing Configuration - The Zipkin Connection
```properties
management.zipkin.tracing.endpoint=http://my-tempo:9411/api/v2/spans
management.tracing.sampling.probability=1.0
```

**What's happening here**:
- Spring Boot thinks it's sending traces to a Zipkin server
- But it's actually sending to Tempo's Zipkin-compatible endpoint
- `/api/v2/spans` is the standard Zipkin ingestion API path
- Tempo translates Zipkin format to its own internal format

**The Zipkin Protocol Flow**:
```
Spring Boot App → Zipkin JSON/Protobuf → Tempo:9411 → Tempo Internal Format → Storage
```

**Dependencies Role**:
- `micrometer-tracing-bridge-brave`: Creates spans using Brave (Zipkin's Java library)
- `zipkin-reporter-brave`: Handles HTTP transport to Zipkin endpoint
- These libraries don't know they're talking to Tempo - they think it's Zipkin!

### Metrics Configuration
```properties
management.endpoints.web.exposure.include=*
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```
- All actuator endpoints exposed including `/actuator/prometheus`
- Histogram metrics enabled for detailed latency analysis

### Correlation Configuration
```properties
logging.pattern.correlation=[${spring.application.name:},%X{traceId:-},%X{spanId:-}]
```
- Logs include trace and span IDs for correlation
- Grafana can jump from traces to logs and vice versa

## Benefits of This Architecture

1. **Complete Observability**: Metrics, logs, and traces in one unified view
2. **Correlation**: Jump between metrics, traces, and logs seamlessly
3. **Performance Insights**: RED metrics automatically generated from traces
4. **Troubleshooting**: Full request flow visibility with distributed tracing
5. **Scalability**: Each component can be scaled independently
6. **Standardization**: Uses industry-standard tools and protocols

This setup gives you enterprise-grade observability for your Spring Boot application with minimal configuration overhead!