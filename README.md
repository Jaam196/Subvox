# SubVox 2.0 — lector de subtítulos en tiempo real

Aplicación Android nativa (Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 36`) que **vigila la pantalla
mientras ves anime o vídeos, reconoce el subtítulo con OCR local (ML Kit) y lo lee en voz alta**. Funciona
con cualquier navegador o reproductor, sin root y sin enviar capturas a ningún servidor.

---

## Qué cambió respecto a la versión anterior

### 1. Lectura de pantalla (el motor)

| Antes | Ahora |
|---|---|
| Cada ~400 ms: captura → preprocesado → OCR, **siempre**, haya o no subtítulo. | Se vigila la pantalla ~7 veces/s con un análisis ligero y **el OCR solo se ejecuta cuando el subtítulo aparece o cambia**. |
| Había que ver el mismo texto en 2 lecturas seguidas (≥ 1 s de retraso, más el tiempo del OCR). | Se espera a que acabe el fundido (texto estable) y se lee a la primera. Retraso típico: 1 sondeo + 1 OCR. |
| En modo «Automático» de idioma se probaban hasta 4 reconocedores seguidos en **cada** frame vacío. | Primero el último idioma que funcionó; los demás, como mucho una vez cada 1,5 s y solo si hay texto. |
| Zona automática: OCR de pantalla completa para «calibrar» (lento, falla a menudo). | La zona se localiza **sin OCR** buscando píxeles claros con contorno oscuro (el aspecto de un subtítulo) y se recorta el OCR al texto exacto. Funciona con texto blanco, amarillo, cian…, en horizontal y en vertical. |
| Captura reducida a 1280 px y luego reescalada; píxel a píxel con 4 lecturas por píxel. | Captura hasta 1920 px, una sola copia de memoria de la franja y muestreo directo del buffer. |
| El panel flotante salía en la captura: el OCR podía leer **su propio texto**. | Las ventanas del panel llevan `FLAG_SECURE`: no aparecen en la captura. |
| La misma frase repetida más tarde (otro personaje diciendo «¿Qué?») podía no leerse. | Si el subtítulo desaparece y vuelve (> 1,2 s) se lee de nuevo. Un parpadeo breve no se repite. |
| Si un subtítulo aparecía a medias («No puedo…») y luego completo, se ignoraba el completo. | La frase completa se lee entera. |
| Lecturas OCR dudosas (palabra perdida) se hablaban dos veces. | Si la confianza es baja o la lectura es «parecida pero distinta», se pide una segunda lectura. |

Código: `capture/TextBandAnalyzer.kt` (detector, lógica pura y probada), `capture/ScreenCaptureManager.kt`,
`ocr/*`, `domain/pipeline/SubtitleDeduplicator.kt` y el bucle `scanStep` de `service/SubtitleReaderService.kt`.

### 2. Voz

- **Causa del sonido robótico**: la voz neuronal Piper estaba configurada con `noise_scale 0.333` y
  `noise_w 0.45`, cuando cada modelo recomienda `0.667` / `0.8` (está en su `.onnx.json`). Valores bajos
  aplanan la entonación. Además la voz neuronal venía **desactivada** y se usaba la voz del sistema.
  Ahora la voz natural es la predeterminada (con aviso de descarga) y usa los valores correctos.
- **Voces nuevas de calidad alta** (hash SHA-256 verificado): *Claude* (es-MX, Apache-2.0) y *Daniela* (es-AR, CC BY-SA 4.0).
  Se mantienen Sharvard, Ald y Davefx.
- **Reproducción en streaming**: empieza a sonar con la primera frase, sin esperar a sintetizar todo.
- **Velocidad adaptativa**: si los subtítulos cortan la voz, acelera suavemente hasta +30 % y vuelve a la
  velocidad normal cuando va holgada (se puede desactivar).
- La carga del modelo ya no puede hacerse dos veces a la vez ni liberarse mientras suena.
- Las frases sin punto final se cierran con uno para que la entonación no quede «colgada».
- **Voz sobre el vídeo**: «Bajar el volumen del vídeo mientras habla» ahora viene activado por defecto.

### 3. Interfaz nueva

Diseño propio (tema «tinta nocturna + violeta»), navegación inferior **Escuchar · Voz · Lectura · Más**:
botón principal con animación, tarjeta «Ahora suena» con resaltado palabra a palabra y modo estudio,
mezcla de audio rápida, selector de voz con descarga/progreso, ajustes de lectura agrupados y un panel
flotante rediseñado. Se eliminaron los 17 temas, las 8 fuentes extra y las imágenes con derechos de autor
(el icono antiguo usaba arte de *Evangelion*); el icono es ahora un vector original.

---

## Estructura

```
app/src/main/java/com/example/
├── MainActivity.kt                   # Permisos y arranque de la UI
├── capture/
│   ├── ScreenCaptureManager.kt       # MediaProjection + sondeo barato + recorte
│   ├── TextBandAnalyzer.kt           # Detección de la banda de texto y diferencia entre frames
│   └── RoiDetector.kt                # Filtro de textos de interfaz (reloj, URLs…)
├── ocr/                              # ML Kit (Latino, Japonés, Coreano, Chino) + preprocesado
├── domain/pipeline/                  # Normalizador, similitud, deduplicador
├── service/SubtitleReaderService.kt  # Foreground service: bucle de escaneo y pipeline de lectura
├── tts/                              # Sistema, Piper offline (Sherpa-ONNX) y Gemini
├── overlay/                          # Panel flotante y editor de zona
└── ui/                               # Pantallas Compose (Home, Voice, Reading, More) + tema
```

## Compilar

1. JDK 17 y Android SDK (`compileSdk 36`). Abre la carpeta en Android Studio y sincroniza.
2. El motor de voz neuronal necesita `app/libs/sherpa-onnx-1.13.8.aar`. Gradle lo descarga con `curl`
   la primera vez; si falla, bájalo de <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8> y colócalo ahí.
3. `gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
4. Pruebas: `gradle :app:testDebugUnitTest`.

> El `applicationId` no cambió. Si Android rechaza la instalación por firma distinta, desinstala antes la versión
> anterior. Los ajustes de velocidad de escaneo y de voz usan claves nuevas: empiezan con los valores nuevos aunque actualices encima.

## Uso

1. Abre SubVox, concede notificaciones y «Mostrar sobre otras apps», y pulsa el botón grande.
2. Acepta el aviso de grabación de pantalla de Android y abre tu reproductor.
3. En **Voz**, descarga la voz natural (una sola vez, ≈ 67 MB) y pulsa «Probar esta voz».
4. Si hay carteles o texto de interfaz que molestan: **Lectura → Zona de subtítulos → Manual → Ajustar en pantalla**.

## Limitaciones

- Contenido con DRM / `FLAG_SECURE` (Netflix, Prime…) se ve negro para cualquier app de captura; SubVox lo avisa.
- Subtítulos de texto oscuro sobre fondo claro no los detecta el análisis rápido; la «red de seguridad»
  hace un OCR de toda la zona cada 4 s por si acaso (desactivada en ahorro de batería). Para ese caso es mejor la zona manual.
- La voz «alta calidad» *Daniela* (≈ 115 MB) es lenta en móviles modestos; el motor avisa y cae a la voz del sistema.

## Créditos y licencias

ML Kit (Google) · Sherpa-ONNX (k2-fsa) · Piper (rhasspy): *Claude* (Apache-2.0), *Daniela* (CC BY-SA 4.0, OpenSLR 61),
*Sharvard*, *Ald*, *Davefx* · Plus Jakarta Sans (OFL).

## Temas

En **Más → Temas** hay seis temas completos. Cada uno cambia colores, tipografía, forma de tarjetas y botones, fondo animado, efectos y el panel flotante que aparece sobre el vídeo:

| Tema | Estilo |
| --- | --- |
| Neón Callejero | Amarillo ácido, cian y rojo sobre negro; lluvia de datos, skyline, glitch, esquinas cortadas |
| Unidad Púrpura | Cabina de mando: rejilla hexagonal, radar giratorio, franjas de alerta, barra de sincronización |
| Aurora | El original: manchas de luz violeta, menta y coral (sigue el modo claro/oscuro) |
| Atardecer Sintético | Sol retro con rendijas, rejilla en perspectiva, estrellas, neón rosa |
| Abismo | Océano profundo: rayos de luz, burbujas, plancton luminoso |
| Sakura | Tema claro: pétalos cayendo, papel y tinta, letra con serifa |

Las animaciones se pueden apagar en la misma pantalla (ahorra batería). Los temas viven en `ui/theme/AppTheme.kt`
(paletas, tipografías, estilo del panel flotante) y `ui/theme/ThemedBackground.kt` (fondos animados).

## Saltar openings y aprender del OCR

- **Saltar openings/endings** (Lectura → Filtros): no lee líneas con ♪/♫ ni las que llegan justo después de dos líneas marcadas; también descarta rótulos de staff ("Música:", "Directed by…").
- **Correcciones del OCR** (Más → Correcciones): en el Historial pulsa el lápiz, edita la frase y la app aprende el cambio (palabras completas) para aplicarlo a las siguientes. También se pueden añadir reglas a mano.
