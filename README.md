# 🏥 Production-Ready Patient Management System  
### Java Spring Boot Microservices | Kafka | gRPC | Docker | AWS

A cloud-native, distributed Patient Management System built using **Java Spring Boot Microservices Architecture** with secure authentication, event-driven communication, containerization, and AWS deployment infrastructure.

This project demonstrates real-world backend engineering concepts including:

- Microservices Architecture
- API Gateway
- JWT Authentication & Authorization
- Kafka Event Streaming
- gRPC Inter-Service Communication
- Docker Containerization
- AWS ECS Deployment
- Load Balancing
- Integration Testing
- Cloud Infrastructure Setup

---

# 🚀 Tech Stack

## Backend
- Java 21
- Spring Boot
- Spring Security
- Spring Cloud Gateway
- Spring Data JPA
- Hibernate

## Communication
- REST APIs
- gRPC
- Protocol Buffers
- Apache Kafka

## Database
- PostgreSQL
- AWS RDS

## DevOps & Cloud
- Docker
- Docker Compose
- AWS ECS
- AWS VPC
- AWS Application Load Balancer
- AWS MSK (Kafka)
- AWS CloudFormation
- LocalStack

## Testing
- JUnit
- Integration Testing
- Postman

---

# 📌 Features

✅ JWT-Based Authentication  
✅ Secure API Gateway Routing  
✅ Distributed Microservices Architecture  
✅ Event-Driven Communication using Kafka  
✅ gRPC-Based Internal Service Calls  
✅ Dockerized Services  
✅ AWS Deployment Infrastructure  
✅ Centralized Exception Handling  
✅ Request Validation  
✅ Integration Testing  
✅ Load Balanced Deployment  

---

# 🏗️ System Architecture

## 🔹 Development Architecture

```text
Frontend Client
       │
       ▼
┌───────────────────┐
│    API Gateway    │
└───────────────────┘
       │
       ▼
┌──────────────────────────────────────────────┐
│            Docker Network                   │
│                                              │
│  ┌───────────────┐       ┌───────────────┐  │
│  │ Auth Service  │       │ Billing Svc   │  │
│  │ JWT Security  │       │ gRPC Server   │  │
│  └───────────────┘       └───────────────┘  │
│                                              │
│          ┌─────────────────────┐             │
│          │  Patient Service    │             │
│          │ gRPC Client         │             │
│          │ Kafka Producer      │             │
│          └─────────────────────┘             │
│                     │                        │
│                     ▼                        │
│              Kafka Topic                    │
│                     │                        │
│      ┌──────────────┴──────────────┐         │
│      ▼                             ▼         │
│ Analytics Service         Notification Svc   │
│ Kafka Consumer            Kafka Consumer     │
└──────────────────────────────────────────────┘


---

# ☁️ AWS Deployment Architecture


Frontend Client
       │
       ▼
┌─────────────────────────────┐
│ Application Load Balancer   │
│        (Public Subnet)      │
└─────────────────────────────┘
               │
               ▼

══════════════════════════════════════════════
               ECS Cluster
══════════════════════════════════════════════

┌────────────────┐
│ API Gateway    │
│ ECS Service    │
└────────────────┘

┌────────────────┐
│ Auth Service   │
│ ECS Service    │
└────────────────┘

┌────────────────┐
│ Billing Service│
│ gRPC Server    │
└────────────────┘

┌────────────────┐
│ Patient Service│
│ Kafka Producer │
│ gRPC Client    │
└────────────────┘

┌────────────────┐
│ Analytics Svc  │
│ Kafka Consumer │
└────────────────┘

══════════════════════════════════════════════

        │                         │
        ▼                         ▼

┌────────────────┐      ┌──────────────────┐
│ AWS RDS        │      │ AWS MSK Cluster  │
│ PostgreSQL DBs │      │ Kafka Topics     │
└────────────────┘      └──────────────────┘
