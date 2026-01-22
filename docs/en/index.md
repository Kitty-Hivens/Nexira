# Aura Launcher Developer Wiki

> **Status:** Active Maintenance
> **Version:** 1.2.3-dev

Welcome to the official technical documentation for **Aura Launcher**.
This wiki is intended for core contributors and maintainers. It provides a deep dive into the architecture, subsystems, and design decisions behind the project.

## 🧭 Navigation

### 🏗️ Core Architecture

Understanding the high-level design and how code is organized.

* **[Architecture & Design](architecture.md)**
* *Topics:* Clean Architecture layers, Module breakdown, Tech stack.


* **[Dependency Injection](dependency-injection.md)**
* *Topics:* Koin setup, Service wiring, Adding new components.



### ⚙️ Internal Systems

The "engine room" of the launcher.

* **[Process Lifecycle](process-lifecycle.md)**
* *Topics:* Launch pipeline, File verification (Hashing), Java runtime checks, Process monitoring.


* **[Networking & Authentication](networking-auth.md)**
* *Topics:* Ktor/OkHttp stack, Login flow, Server list fetching, Session management.


* **[Data Persistence](data-storage.md)**
* *Topics:* JSON configuration files, Directory structure, Profile management.



### 🎨 User Interface

Visuals, rendering, and user experience.

* **[UI & Theming Engine](ui-theming.md)**
* *Topics:* Compose Multiplatform, Seasonal themes (`SeasonTheme`), Particle system, Custom components.



---

## 🚀 Quick Start for Contributors

1. **Prerequisites:**
* JDK 21+
* IntelliJ IDEA (recommended)
* Git


2. **Build the Project:**
```bash
# Run from root
./gradlew :client-ui:packageDistribution

```


3. **Code Style:**
* Follow standard Kotlin coding conventions.
* Use `koinInject()` only in top-level Composables or Controllers.
* Do not put logic in UI components; use Controllers.



---

## ⚖️ License

Aura Launcher is open-source software licensed under the **GNU GPL v3**.
See the [LICENSE](../../LICENSE) file for more details.

*Documentation maintained by the Hivens Team.*
