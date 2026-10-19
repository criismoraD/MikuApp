# MikuApp — Corrección de Arranque y Migración de Entorno Android

Este plan resuelve el fallo de inicio del entorno de desarrollo en AI Studio alineando la cadena de herramientas de Android (Gradle, AGP y SDK), configurando la firma de depuración, integrando Secrets Gradle Plugin para `GEMINI_API_KEY` y automatizando la obtención de `sherpa-onnx` y el modelo de voz local.

## User Review & Critical Decisions

> [!IMPORTANT]
> Decisiones confirmadas durante la fase de clarificación para garantizar que la aplicación compile y mantenga su funcionalidad completa de voz y conexión con Gemini Live.

- **Motor TTS (`sherpa-onnx` + Modelo VITS)**: Se configurará una tarea en Gradle (`prepareTtsAssets`) que descargue automáticamente `sherpa-onnx-1.13.8.aar` desde GitHub Releases a `app/libs/` y el modelo `es_MX-claude-high.onnx` si no están presentes localmente (ya que estaban en `.gitignore`). Si en algún entorno sin red la descarga del modelo falla, `MikuViewModel` activará automáticamente la voz nativa de Gemini (`useGeminiVoice = true`) como respaldo seguro.
- **Gestión de Claves (`GEMINI_API_KEY`)**: Se reemplazará la lectura manual de `local.properties` por el plugin oficial **Secrets Gradle Plugin** (`com.google.android.libraries.mapsplatform.secrets-gradle-plugin`), leyendo de `.env` y `.env.example`.

---

## 1. Overview & Core Concept

- **What It Does**: MikuApp es un asistente vocal interactivo en tiempo real basado en Hatsune Miku que conecta con Gemini Live API mediante WebSockets, procesa audio bidireccional, analiza video desde la cámara con CameraX, detecta emociones para cambiar expresiones faciales y sintetiza voz local con efecto Vocaloid mediante `sherpa-onnx`.
- **Target Audience / Persona**: Usuarios que buscan una experiencia conversacional multimodal en tiempo real con un avatar expresivo en Android.
- **Key Value**: Restaurar la compilación limpia (`assembleDebug`) y ejecución inmediata en el emulador de AI Studio sin perder el motor TTS ni la integración con Gemini Live.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Inicialización**: Al abrir la app, se carga el motor TTS local (o se conmuta automáticamente a la voz nativa de Gemini si el archivo ONNX no está disponible) mostrando el estado en el encabezado.
  2. **Conexión Multimodal**: El usuario pulsa Conectar (`▶`), concede permiso de micrófono y establece sesión WebSocket con Gemini Live.
  3. **Interacción por Voz y Cámara**: El avatar reacciona a la amplitud del audio y cambia entre 11 expresiones según el contexto emocional; la cámara superpone una vista previa en tiempo real.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Se preserva íntegramente el diseño original oscuro inspirado en Hatsune Miku (`MikuCyan` `#39C5BB`, `MikuBackground` `#03100E`, `MikuSurface` `#08251F`).
  - *Component Styling & Layout*: Interfaz Jetpack Compose de pantalla completa con avatar central reactivo, panel de subtítulos y barra inferior de tres controles circulares ergonómicos.
- **Interactive Feedback & Motion**: Escalado elástico del avatar sincronizado con el RMS del audio, halo radial luminoso y panel de depuración (`DBG`) con registro en vivo y pruebas de voz/conexión.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Alineación de Toolchain con el Contenedor de AI Studio**
  - *Chosen Approach*: Ajustar `gradle-wrapper.properties` a Gradle `9.3.1`, `agp` a `9.1.1` en el catálogo de versiones y `compileSdk`/`targetSdk` a `36` (con `coreKtx = "1.16.0"`, `lifecycleRuntimeKtx = "2.9.0"`, `activityCompose = "1.10.1"`, `composeBom = "2025.02.00"` compatibles con SDK 36).
  - *Why*: El entorno Android de AI Studio provee Android SDK 36, AGP 9.1.1 y Gradle 9.3.1. Las versiones pre-lanzamiento (SDK 37, AGP 9.4.1, Gradle 9.6.0) provocan que el servidor de compilación no pueda iniciar.
  - *Alternatives Considered*: Mantener SDK 37 y AGP 9.4.1 no es viable porque esos artefactos no existen en el SDK instalado del contenedor.
- **Decision 2: Descarga Automática de `sherpa-onnx-1.13.8.aar` en Gradle**
  - *Chosen Approach*: Añadir una tarea de pre-compilación en Gradle que verifique y descargue `sherpa-onnx-1.13.8.aar` en `app/libs/` antes de `preBuild`, además de decodificar `debug.keystore.base64` a `debug.keystore` si no existe.
  - *Why*: El repositorio original ignoraba `*.aar` y `*.onnx` en `.gitignore`, causando fallo inmediato de dependencias al importar el proyecto.
- **Decision 3: Secrets Gradle Plugin + `.env.example`**
  - *Chosen Approach*: Configurar `com.google.android.libraries.mapsplatform.secrets-gradle-plugin` (`2.0.1`) apuntando a `.env` y `.env.example`.
  - *Why*: Estándar requerido por AI Studio para inyectar `BuildConfig.GEMINI_API_KEY` de forma segura sin depender de `local.properties`.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Configuración Gradle                          │
│  ┌──────────────────────┐  ┌───────────────────┐  ┌───────────────┐  │
│  │ Secrets Gradle Plugin│  │ Tarea Descarga AAR│  │ debug.keystore│  │
│  │ (.env / .env.example)│  │ (sherpa-onnx 1.13)│  │   Signing     │  │
│  └──────────┬───────────┘  └─────────┬─────────┘  └───────┬───────┘  │
└─────────────┼────────────────────────┼────────────────────┼──────────┘
              ▼                        ▼                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         MikuApp (Android APK)                        │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                    MainActivity (Compose UI)                   │  │
│  │   MikuHeader ──► MikuAvatar ──► CaptionPanel ──► ControlPanel  │  │
│  └───────────────────────────────┬────────────────────────────────┘  │
│                                  ▼                                   │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                         MikuViewModel                          │  │
│  └──┬─────────────────┬──────────────────┬─────────────────────┬──┘  │
│     ▼                 ▼                  ▼                     ▼     │
│ ┌──────────┐   ┌──────────────┐   ┌──────────────┐   ┌─────────────┐ │
│ │GeminiLive│   │MikuTtsEngine │   │GeminiAudio   │   │CameraVideo  │ │
│ │  Client  │   │ (sherpa-onnx)│   │   Player     │   │  Analyzer   │ │
│ └──────────┘   └──────────────┘   └──────────────┘   └─────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **`BuildConfig.GEMINI_API_KEY`**: Generado automáticamente por Secrets Gradle Plugin desde `.env` / `.env.example`.
- **Estado Reactivo (`MikuViewModel`)**: Flujos `StateFlow` para `connectionState`, `isSpeaking`, `audioAmplitude`, `caption`, `currentExpression` y `useGeminiVoice`.

### Interactive Component & State Mapping
- **Pre-Build Hook**: Antes de compilar (`preBuild`), Gradle garantiza que `debug.keystore`, `app/libs/sherpa-onnx-1.13.8.aar` y el modelo `es_MX-claude-high.onnx` estén listos en el sistema de archivos.
- **Fallback Inteligente de Voz**: Si el motor local `MikuTtsEngine.initialize()` devuelve `false`, `MikuViewModel` activa automáticamente `_useGeminiVoice.value = true` para que Miku hable con la voz de Gemini sin interrumpir la experiencia del usuario.
