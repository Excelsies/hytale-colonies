# HyColonies

**HyColonies** is a server-side modification for Hytale designed to emulate and enhance the colony-management gameplay of *MineColonies*. It empowers players to establish autonomous settlements where Non-Player Characters (NPCs) live, work, and build dynamically.

Unlike its predecessors, HyColonies is engineered strictly for Hytale’s **Server-Authoritative** architecture, utilizing **Java 25** and the **Entity Component System (ECS)** to support high-concurrency simulation without client-side code execution.

## 🏗️ Architecture Highlights

This project adheres to a "One Client" policy, ensuring all functionality operates without requiring players to install client-side mods.

*   **Presentation**: UI is handled via **NoesisGUI** (XAML), and world visualization utilizes server-driven particles and display entities.
*   **Logic (ECS)**: Game logic is decoupled from entity objects. Behavior is defined by Systems iterating over Component data.
*   **Async Logistics**: Supply chain calculations run on worker threads using immutable state snapshots to prevent server tick lag.
*   **Persistence**: Data is stored in human-readable JSON format, decoupled from the world save.

For a detailed technical deep dive, refer to the [Architectural Design Document](HyColonies_%20Architectural%20Design%20Document.md).

## 📋 Prerequisites

*   **Hytale Launcher**: Installed and updated.
*   **Java 25 SDK**: Required for modern Hytale development.
*   **IntelliJ IDEA**: Recommended for full feature support.

## 🚀 Quick Start

### 1. Setup

1.  Open the project in IntelliJ IDEA.
2.  Wait for the Gradle sync to finish. This will automatically download dependencies, create a `./run` folder, and generate the **HytaleServer** run configuration.

### 2. Authenticating the Test Server

You **must** authenticate your local server to connect to it:

1.  Launch the **HytaleServer** configuration in IDEA.
2.  In the terminal, run: `auth login device`.
3.  Follow the printed URL to log in via your Hytale account.
4.  Once verified, run: `auth persistence Encrypted`.

### 3. Running & Verifying

1.  Keep the server running.
2.  Launch your standard Hytale Client.
3.  Connect to `127.0.0.1`.
4.  Type `/test` in-game to verify the plugin is loaded.

## 🛠️ Build Commands

*   **Build Plugin**: `./gradlew build`
    *   Output: `build/libs/{projectName}-{version}.jar`
*   **Build Shadow JAR**: `./gradlew shadowJar`
*   **Run Tests**: `./gradlew test`
*   **Generate VSCode Config**: `./gradlew generateVSCodeLaunch`

## 📦 Project Structure

*   **Source Code**: `src/main/java/`
    *   Entry Point: `com.excelsies.plugin.ExamplePlugin`
*   **Resources**: `src/main/resources/`
    *   `manifest.json`: Plugin metadata.
    *   `Common/`: Shared assets (Models/Textures).
    *   `Server/`: Server-only assets (Recipes, etc).

## 📚 Documentation

*   [Hytale Modding Documentation](https://britakee-studios.gitbook.io/hytale-modding-documentation)
