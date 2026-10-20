# ARTEMIS en MikuApp — Control total del teléfono, 100 % nativo

Implementación inspirada en la arquitectura **"Flash Profile"** de
[Google ARTEMIS](https://github.com/google/artemis): un agente autónomo que
**observa** la pantalla, **piensa** con Gemini y **actúa** sobre el teléfono a
través de un `AccessibilityService`. No necesita PC, cables ni ADB.

---

## 1. Arquitectura (Observe → Think → Act)

```
        VOZ (Gemini 3.8 Live)                AGENTE (Gemini 3.5 Flash Lite)
        ─────────────────────                ──────────────────────────────
        "abre WhatsApp y lee…"                 ┌──────────────────────────┐
              │  toolCall: control_phone       │   ArtemisAgentLoop        │
              ▼                                │   (singleton)             │
        MikuViewModel ───────────────────────► │                           │
              ▲                                └──┬─────────┬─────────────┘
              │  sendTextMessage (TTS)            │         │
              └── resultado de la tarea     OBSERVAR    PENSAR ──── ACTUAR
                                                 │         │          │
                              MikuAccessibilityService  GeminiText    ActionExecutor
                              · snapshot() árbol UI     AgentClient   · click / typeText
                              · overlay flotante        · generate    · swipe / globalAction
                              · captura (emergencia)      Content     · openApp
                                                        · mode=ANY
```

Por cada paso:

1. **Observar** — `snapshot()` recorre `rootInActiveWindow`, filtra nodos
   visibles e interactivos (o con texto) y les asigna índices `[1], [2], [3]…`
   con clase, texto, `contentDescription`, `viewIdResourceName` y centro
   `(centerX, centerY)` de `getBoundsInScreen()`. La captura JPEG solo se adjunta
   como emergencia cuando el árbol llega casi vacío (< 5 nodos).
2. **Pensar** — `GeminiTextAgentClient` llama a **Gemini 3.5 Flash Lite**
   (`generateContent`) con **function calling forzado** (`toolConfig.mode=ANY`):
   cada paso termina en una herramienta, sin texto libre ni audio. El cliente es
   *sin estado*: cada petición lleva la observación actual (tarea + historial de
   las últimas 8 acciones + árbol UI) y no acumula contexto. Respuesta típica:
   **~2 segundos por decisión**.
3. **Actuar** — `ActionExecutor` ejecuta la herramienta devuelta.
4. **Estabilizar** — 0,7 s de espera tras cada acción antes de volver a
   observar. Límite estricto de **15 pasos** por tarea.

Mientras el agente trabaja, el `AccessibilityService` dibuja un **overlay
flotante** (`TYPE_ACCESSIBILITY_OVERLAY`, sin permisos extra) con el paso actual
y la última acción: se ve por encima de WhatsApp o de cualquier app.

Al terminar, el resultado se devuelve a la voz (`MikuViewModel`) y la sesión
Live de Miku lo dice con TTS local.

---

## 2. Archivos

| Archivo | Rol |
| --- | --- |
| `app/src/main/res/xml/miku_accessibility_service_config.xml` | Configuración del servicio (`canRetrieveWindowContent`, `canPerformGestures`, `canTakeScreenshot`, `flagReportViewIds`). |
| `agent/MikuAccessibilityService.kt` | Extracción del árbol UI indexado, mapa índice → nodo, captura JPEG/Base64 y overlay flotante de progreso. |
| `agent/ActionExecutor.kt` | Motor de acciones: clic semántico con *fallback* a coordenadas, texto (`ACTION_SET_TEXT` + portapapeles), gestos, botones globales y `openApp`. |
| `agent/AgentBrain.kt` | Contrato del "cerebro" (`AgentToolCall`, `AgentDecision`) común a texto y Live. |
| `agent/GeminiTextAgentClient.kt` | Cerebro principal: **Gemini 3.5 Flash Lite** (`generateContent`) con function calling forzado y cliente sin estado. |
| `agent/GeminiLiveAgentClient.kt` | Cerebro de respaldo opcional: sesión Live con **Gemini 3.8 Live** (`bidiGenerateContent`). |
| `agent/AgentToolSchema.kt` | Las 7 herramientas de function calling y el System Prompt ARTEMIS. |
| `agent/ArtemisAgentLoop.kt` | Bucle Observe-Think-Act, límite de pasos, notificación con parada de emergencia y overlay. |
| `agent/AgentStopReceiver.kt` | Parada de emergencia desde la notificación. |
| `agent/MikuAgentJsBridge.kt` | Puente opcional para UI Web/React/Capacitor embebida. |
| `agent/AgentModels.kt` | Modelos de nodo, snapshot, resultado y estado. |

En `AndroidManifest.xml` se registra el servicio con
`android.permission.BIND_ACCESSIBILITY_SERVICE` (solo el sistema puede
vincularlo) y el receptor de parada.

---

## 3. Cómo activarlo

1. Compila e instala:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

2. En el teléfono: **Ajustes → Accesibilidad → Miku Control Total → Activar**
   (o el chip **"Activar control total"** de la cabecera de MikuApp).
3. Úsalo de tres formas:
   - **Por voz** (flujo principal): "Miku, abre WhatsApp y escribe a mamá que
     llegaré tarde". Miku llama a la herramienta `control_phone` y delega la
     tarea al agente; al terminar le dice el resultado con su voz.
   - **Panel de diagnóstico** (engranaje): campo de texto + "Iniciar control".
   - **Puente JS** desde una WebView de confianza (ver §6).

---

## 4. Herramientas de Gemini

| Herramienta | Parámetros | Acción |
| --- | --- | --- |
| `tap_element` | `index?`, `x?`, `y?`, `reason` | `ACTION_CLICK` sobre el nodo; si falla, tap con `dispatchGesture`. |
| `input_text` | `index`, `text`, `clear_first?`, `reason` | Enfoca, `ACTION_SET_TEXT`; *fallback* portapapeles + `ACTION_PASTE`. |
| `swipe_screen` | `direction` (`UP/DOWN/LEFT/RIGHT`), `reason` | `dispatchGesture` con trazo de 320 ms. |
| `press_system_button` | `button` (`BACK/HOME/RECENTS/NOTIFICATIONS`), `reason` | `performGlobalAction`. |
| `launch_app` | `app_name`, `reason` | Busca por etiqueta/paquete (con alias y normalización de acentos) y lanza el `launchIntent`. |
| `wait_seconds` | `seconds`, `reason` | Espera 1–15 s. |
| `complete_task` | `success`, `final_response_to_user` | Termina el bucle y devuelve la respuesta con la personalidad de Miku. Solo cuando la orden completa está cumplida (no al abrir la app). |

El **System Prompt** del modelo es exactamente el de la especificación
"Mentalidad ARTEMIS" solicitada (estrategia *Dynamic-First, Coordinate-Fallback*,
no repetir acciones sin efecto), reforzado con reglas para tareas de mensajería:
buscar el contacto, abrir el chat, escribir y enviar antes de terminar; y para
leer conversaciones devolviendo el contenido fiel en la respuesta final.

---

## 5. Seguridad y parada de emergencia

- **Límite de 15 pasos** por tarea; después el agente se detiene solo.
- **Overlay flotante de progreso** con el paso actual, visible sobre otras apps.
- **Botón `DETENER CONTROL`** en el overlay de la app.
- **Notificación persistente** con acción `DETENER` (funciona aunque estés en
  otra app; requiere permiso de notificaciones en Android 13+).
- **Comando de voz**: "detente", "cancela", "aborta", "para la tarea"…
- Si una acción no cambia la pantalla, el historial y la advertencia de
  pantalla sin cambios evitan que el modelo repita el mismo clic.

---

## 6. Puente Web / React / Capacitor (opcional)

La UI principal de MikuApp es **Compose nativa**, así que el puente solo hace
falta si embebes una WebView propia. `MikuAgentBridge.attachIfTrusted()` lo
adjunta **únicamente** a orígenes de confianza (`file://`, `localhost`,
`127.0.0.1`, `capacitor://localhost`, `ionic://localhost`); nunca a contenido
remoto como YouTube.

```kotlin
AndroidView(factory = { context ->
    WebView(context).apply {
        // Importante: adjuntar antes de cargar la URL.
        MikuAgentBridge.attachIfTrusted(this, "http://localhost:3000/", context)
        loadUrl("http://localhost:3000/")
    }
})
```

Contrato JS:

```js
window.MikuAgent.startTask("abre WhatsApp y escribe hola")
window.MikuAgent.stopTask()
window.MikuAgent.getStatus()          // JSON con running/step/lastAction…
window.MikuAgent.isAccessibilityEnabled()
window.MikuAgent.openAccessibilitySettings()
```

> En Capacitor, el equivalente sería un plugin que exponga estos mismos
> métodos; el `AccessibilityService` y el bucle no cambian.

---

## 7. Configuración y privacidad

- La clave se lee de `.env` (`GEMINI_API_KEY=…`), que está en `.gitignore`.
  Se incrusta en `BuildConfig`, por lo que **no debe publicarse la APK** con tu
  clave; para distribución usa un proxy propio.
- **Modelos**:
  - Agente (control del teléfono): **`models/gemini-3.5-flash-lite`** vía
    `generateContent` con function calling forzado (`mode=ANY`). Es el cerebro
    rápido y estructurado del bucle.
  - Voz de Miku: **`models/gemini-3.8-live`** en su propia sesión Live (audio
    nativo). La voz no decide pasos: delega con `control_phone`.
  - Respaldo: `GeminiLiveAgentClient` implementa el mismo contrato `AgentBrain`
    con `models/gemini-3.8-live`, disponible por si se quiere volver al cerebro
    Live sin tocar el bucle.
- Todo el control ocurre en el dispositivo; el árbol UI (y la captura, solo en
  pantallas sin texto accesible) se envían a la API de Gemini como contexto
  para la decisión.

Este código es una implementación independiente inspirada en la arquitectura
de Google ARTEMIS; no reutiliza código del repositorio original.
