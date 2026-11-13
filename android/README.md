# PDA // ZONA — módulo Android

La aplicación Android es una **envoltura nativa con WebView** que abre la PWA publicada por HTTPS:

`https://guillewestdorp50-boop.github.io/pda-zona./`

No es una Trusted Web Activity (TWA) en su implementación actual. El archivo `index.html` que permanece en la raíz de esta rama es una copia histórica de trabajo y no es el contenido que carga el lanzador Android al iniciar. La PWA publicada sigue siendo la interfaz principal.

## Límites de esta rama

- Los cambios Android se realizan únicamente en `android-pda-native`.
- La PWA madre y sus archivos publicados no se modifican desde esta rama.
- No se borran ni migran datos de usuario ni se modifican tablas, funciones o políticas de Supabase.
- El WebView conserva su propio almacenamiento de navegador. No comparte automáticamente la sesión anónima ni el almacenamiento local de Chrome/PWA; la identidad de sesión puede ser distinta entre ambos entornos.
- Los permisos de ubicación, cámara y micrófono se solicitan al sistema Android solamente cuando una función los necesita.
- La selección de fotos y archivos se entrega al selector seguro de Android.
- La orientación física la administra Android; la PDA solo informa si la pantalla está vertical u horizontal.
- La ventana usa modo inmersivo y contenido edge-to-edge.

## Funciones Android

- Adaptador de voz nativa de Lebedev mediante Android Text-to-Speech.
- Selector de voz persistente en preferencias locales de la aplicación.
- Widget nativo de inicio como acceso rápido a la PDA.
- WebView con JavaScript, DOM Storage, caché estándar y restauración del estado.
- Integración de selector de archivos, GPS y permisos multimedia.

## Pendientes conocidos

- El widget es por ahora un acceso rápido; todavía no presenta clima ni alertas en vivo.
- Las notificaciones push y la actualización automática del widget requieren una integración posterior con un backend y suscripciones por dispositivo.
- El workflow actual genera una APK de depuración; no es todavía una versión firmada de distribución.
- La asociación Digital Asset Links y una TWA real no forman parte de la implementación actual.
- La sesión de WebView es independiente de Chrome. No se debe asumir que la foto o el perfil de Chrome aparecerán automáticamente en Android si la cuenta anónima es distinta.

## Compilación

El workflow de GitHub Actions `.github/workflows/android-debug.yml` compila con Java 17 y publica el artefacto `pda-zona-android-debug`. Una compilación exitosa confirma que el proyecto se construye, pero no sustituye las pruebas en un teléfono Android real.

Antes de considerar estable una APK hay que probar, como mínimo, giro de pantalla, modo inmersivo, teclado, selector de fotos, GPS, voz de Lebedev, navegación, persistencia de sesión y carga de datos.
