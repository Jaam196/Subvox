# Cambios de SubVox

## v2.1.3
- La voz ya no corta la frase cuando aparece el siguiente subtítulo: por defecto termina la frase y acelera la siguiente si se va atrasando (configurable en Lectura).
- Aceleración más fuerte cuando se acumulan frases (hasta +45 %); como mucho 2 frases en espera para no ir desfasado del vídeo.
- La voz del sistema también acelera cuando hay frases esperando.
- Si el OCR leyó media frase y luego aparece completa, solo se lee la parte que faltaba (antes se repetía o se cortaba).

## v2.1.2
- Corregido: cierre nativo al liberar la voz descargada mientras todavía estaba generando audio (al salir de la app, parar el lector o cambiar de pantalla). Ahora espera a que termine la síntesis.

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
