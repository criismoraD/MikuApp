# ARTEMIS en MikuApp — Control total del teléfono, 100 % nativo

Implementación inspirada en la arquitectura **"Flash Profile"** de
[Google ARTEMIS](https://github.com/google/artemis): un agente autónomo que
**observa** la pantalla, **piensa** con Gemini y **actúa** sobre el teléfono a
través de un `AccessibilityService`. No necesita PC, cables ni ADB.

---

## 1. Arquitectura (Observe → Think → Act)

```
            ┌────────────────────────────────────────────────────┐
            │                ArtemisAgentLoop                     │
            │  (singleton, sobrevive a la Activity)               │
            └───────┬───────────────┬────────────────┬────────────┘
                    │               │                │
              OBSERVAR          PENSAR            ACTUAR
                    │               │                │
     MikuAccessibilityService   GeminiAgentClient   ActionExecutor
     · snapshot() árbol UI      · generateContent   · click / typeText
     · captura JPEG Base64      · Function Calling  · swipe / globalAction
     · índice [1..N]            · historial         · openApp (PackageManager)
```

Por cada paso:

1. **Observar** — `snapshot()` recorre `rootInActiveWindow`, filtra nodos
   visibles e interactivos (o con texto) y les asigna índices `[1], [2], [3]…`
   con clase, texto, `contentDescription`, `viewIdResourceName` y centro
   `(centerX, centerY)` de `getBoundsInScreen()`. Se adjunta una captura JPEG
   en Base64 (API 30+) como respaldo visual.
2. **Pensar** — `GeminiAgentClient` envía la tarea original, el historial de
   las últimas 8 acciones y el estado actual (nodos + imagen) a
   `generateContent` con *function calling*.
3. **Actuar** — `ActionExecutor` ejecuta la herramienta devuelta.
4. **Estabilizar** — 1,5 s de espera tras cada acción antes de volver a
   observar. Límite estricto de **15 pasos** por tarea.

---

## 2. Archivos

| Archivo | Rol |
| --- | --- |
| `app/src/main/res/xml/miku_accessibility_service_config.xml` | Configuración del servicio (`canRetrieveWindowContent`, `canPerformGestures`, `canTakeScreenshot`, `flagReportViewIds`). |
| `agent/MikuAccessibilityService.kt` | Extracción del árbol UI indexado, mapa índice → nodo y captura JPEG/Base64. |
| `agent/ActionExecutor.kt` | Motor de acciones: clic semántico con *fallback* a coordenadas, texto (`ACTION_SET_TEXT` + portapapeles), gestos, botones globales y `openApp`. |
| `agent/GeminiAgentClient.kt` | Cliente REST de Gemini con las 7 herramientas y el System Prompt ARTEMIS. |
| `agent/ArtemisAgentLoop.kt` | Bucle Observe-Think-Act, límite de pasos, notificación con parada de emergencia. |
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

2. En el teléfono: **Ajustes → Accesibilidad → Miku Control Total → Activar**.
3. Úsalo de tres formas:
   - **Por voz**: "Miku, abre WhatsApp y escribe a mamá que llegaré tarde".
     Miku llama a la herramienta `control_phone` y delega la tarea al bucle.
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
| `complete_task` | `success`, `final_response_to_user` | Termina el bucle y devuelve la respuesta con la personalidad de Miku. |

El **System Prompt** del modelo es exactamente el de la especificación
"Mentalidad ARTEMIS" solicitada (estrategia *Dynamic-First, Coordinate-Fallback*,
no repetir acciones sin efecto, etc.).

---

## 5. Seguridad y parada de emergencia

- **Límite de 15 pasos** por tarea; después el agente se detiene solo.
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
- Modelo del bucle: `gemini-3.8-flash` (constante `GeminiAgentClient.DEFAULT_MODEL`).
  Si el modelo principal responde 503/429 por alta demanda, el cliente
  reintenta automáticamente con `gemini-3-flash-preview`. La conversación de voz
  de Miku sigue usando `gemini-3.8-live`, que solo funciona por WebSocket
  (`bidiGenerateContent`) y no por `generateContent`.
- Todo el control ocurre en el dispositivo; el árbol UI y las capturas se
  envían a la API de Gemini como contexto para la decisión.

Este código es una implementación independiente inspirada en la arquitectura
de Google ARTEMIS; no reutiliza código del repositorio original.
