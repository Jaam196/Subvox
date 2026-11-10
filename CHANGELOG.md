# Cambios de SubVox

## v2.1.1 (revisión)
- Corregido: una voz descargada se borraba sola si la app se cerraba (o se actualizaba) mientras hablaba. Ahora solo se retira si Android confirma un fallo nativo.
- Corregido: el aviso "voz offline lenta" saltaba sin motivo porque contaba el tiempo de reproducción como tiempo de síntesis.
- Corregido: lectura del informe nativo en Android 11 (API que solo existe desde Android 12).
- Menos chasquidos entre trozos de una frase (fundido al final de cada trozo).
- Revisión automática en cada pull request: pruebas unitarias y lint.

## v2.1
- Voces neuronales: síntesis con `generate()` en lugar del callback nativo (evitaba el SIGABRT en Samsung/Android 17).
- Diagnóstico: Más → Acerca de muestra el motivo real del último cierre (CRASH_NATIVE, memoria…).
- Saltar openings/endings y rótulos de staff.
- Correcciones del OCR que aprenden (Historial → lápiz, Más → Correcciones).
- 6 temas, voces expresivas (preguntas, comillas, paréntesis), descargas por voz y atómicas.
