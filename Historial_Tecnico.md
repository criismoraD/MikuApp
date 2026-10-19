# Historial Técnico — MikuApp

## Estado
- Ventanas flotantes activas en Web y Android: YouTube (`Ventana_Youtube`) y Bloc de Notas (`Ventana_Bloc_Notas`).
- Miku mantiene la sesión de escucha y voz abierta mientras YouTube o el Bloc de Notas están en pantalla.
- Comandos de voz bidireccionales integrados en Gemini Live (`open_youtube`, `play_youtube_video`, `close_youtube`, `open_notepad`, `write_notepad`, `close_notepad`).

## Completado
- **Control_Ventana_Youtube:**
  - `server.mjs`: Corregido error de sintaxis regex (`/(?:youtu.be/: Unterminated group`) reemplazando expresiones regulares con métodos seguros de URL y cadenas (`URL.pathname`, `split`, `String.fromCharCode(10)`).
  - `server.mjs`: Reproductor flotante PIP con iframe seguro, título dinámico y botón de cierre. Se queda abierto en pantalla mientras el micrófono sigue activo. Permite buscar y entrar a videos ("entra al video X") o cerrarlo por voz ("cierra youtube").
  - `GeminiLiveClient.kt` & `AndroidCommandExecutor.kt`: Registradas herramientas `open_youtube`, `play_youtube_video` y `close_youtube` para abrir/buscar videos o devolver el foco al frente de MikuApp.
- **Ventana_Bloc_Notas_Interactiva:**
  - `server.mjs`: Ventana flotante estilo bloc de notas con barra de menús, contador de caracteres/líneas, botón de copiado y animación realista de mecanografiado carácter por carácter al dictar/escribir.
  - `MainActivity.kt` & `MikuViewModel.kt`: Composable `MikuNotepadOverlay` con `BasicTextField`, diseño cyberpunk/Vocaloid, portapapeles y sincronización de estado reactivo.
- **Sincronizacion_Audio_Vocal_Lagata:** Pista vocal (01:06) con cache-busting `?t=...`, `no-store` y comando `Detener_Cancion_Voz`.
- **Animacion_Respiracion_Organica:** Respiración continua activa en avatar web y Android Compose.

## Pendiente
- Modo pantalla dividida (split-screen) automático para YouTube en tablets y pantallas anchas.
- Exportación directa de archivos `.txt` del Bloc de Notas al almacenamiento local o Google Drive.
- Reanudar canción interrumpida ("sigue cantando").
