# PDA // ZONA — lanzador Android para Google Chrome

Esta variante del APK es un **lanzador independiente**: al tocar su ícono abre la PDA publicada directamente en la aplicación Google Chrome instalada en el teléfono.

URL abierta:
`https://guillewestdorp50-boop.github.io/pda-zona./`

## Separación y protección de la instalación anterior

- El nuevo APK usa el identificador `com.pdazona.chrome`, distinto de la aplicación anterior `com.pdazona.app`.
- Puede instalarse junto a la PDA anterior; no la actualiza, no la desinstala y no modifica sus datos.
- No se modifica la rama PWA madre `radio-zona-local`, ni archivos publicados, ni tablas o funciones de Supabase.
- La sesión, cookies, almacenamiento local y datos web utilizados por esta variante son los de Google Chrome. No se copian automáticamente desde el almacenamiento aislado del WebView de la APK anterior.
- El lanzador requiere Google Chrome. Si no está instalado o habilitado, ofrece abrir su ficha de Google Play.

## Qué cambia respecto de la APK anterior

- La interfaz y la ejecución web ocurren en Chrome, no en Android WebView.
- La PDA aprovecha la compatibilidad web y la sesión propia de Chrome.
- Los permisos web de ubicación, cámara y micrófono se gestionan desde Chrome y Android.
- Las funciones que dependían del puente nativo exclusivo del WebView anterior (por ejemplo, el diálogo nativo de vinculación y el selector de voz nativo personalizado) no se ejecutan dentro de este lanzador. La voz disponible será la que exponga Chrome/Android.

## Compilación y verificación

El workflow `.github/workflows/android-debug.yml` compila el lanzador y el instalador de diagnóstico en la rama `android-pda-native`. Una compilación exitosa confirma que el APK se construyó; no demuestra todavía que Chrome haya abierto correctamente la PDA en un teléfono real.
