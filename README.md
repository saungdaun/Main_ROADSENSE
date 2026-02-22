# 🚧 RoadSenseBasic

> Digital Pavement Condition Survey & PCI Engine for Android  
> A field-ready prototype for infrastructure monitoring and pavement management research.

Android-Based Road Condition Survey & PCI Calculation System

RoadSenseBasic is a field-based Android application designed for road condition data collection, PCI (Pavement Condition Index) computation, SDI calculation, and survey reporting.

This project aims to support digital transformation in road infrastructure monitoring and pavement management systems.

📌 Key Features
🗺️ 1. GPS-Based Road Survey

Real-time GPS tracking

Road segment mapping

Survey session recording

Foreground service for continuous tracking

📊 2. Pavement Condition Index (PCI)

Distress type classification

Distress severity input

Automatic PCI calculation engine

Segment-based PCI aggregation

📉 3. Surface Distress Index (SDI)

Automated SDI computation

Integrated survey engine

Road condition categorization

🧮 4. Calculation Engine

Modular PCI calculator

SDI engine

Clean architecture (Domain Layer separation)

🗄️ 5. Local Database (Room)

Versioned schema migration

RoadSegment entity

Distress entity

Survey session management

📄 6. Export & Reporting

PDF export

File exporter utility

Summary dashboard

🏗️ Architecture

This project follows clean modular layering:

UI Layer
ViewModel
Repository
Domain Engine (PCI / SDI Calculator)
Room Database

Technologies used:

Kotlin

Android Jetpack

Room Database

Foreground Service

MVVM Pattern

📂 Project Structure Overview
data/
 ├── local/
 │    ├── dao/
 │    ├── entity/
 │    └── RoadSenseDatabase.kt
domain/
 └── engine/
      ├── Pcicalculator.kt
      └── SDICalculator.kt
ui/
 ├── map/
 ├── distress/
 └── summary/
🎯 Target Use Case

Road infrastructure monitoring

Pavement condition survey

Local government field survey

Academic research (PWK / Civil Engineering)

Smart road inspection prototype

📱 Minimum Requirements

Android 8.0+

GPS enabled

Storage permission for report export

🚀 Future Development

Cloud sync (Firebase / Backend API)

GIS integration

Multi-user survey system

Dashboard analytics

Web-based monitoring panel

📌 Project Status

🟢 Active Development
Schema version: v9
PCI Engine: Implemented
SDI Engine: Implemented
PDF Export: Available

👨‍💻 Author

Developed by Hatta
Road Infrastructure & Urban Planning Enthusiast
