# Exploradores — APK Android

Proyecto independiente de PDA//ZONA. Esta carpeta vive en una rama separada y no modifica la rama de producción ni el sitio PDA madre.

## Primera entrega funcional
- Interfaz horizontal prioritaria.
- Mundo de dinosaurios con pronunciación por voz.
- Tablero de botones comunicadores con voz en español.
- Flipper sin derrotas: la pelota rebota en el límite inferior y nunca se pierde.
- Sin cuentas, publicidad, compras, puntuaciones competitivas ni conexión obligatoria.

## Empaquetado
La aplicación final será un APK Android nativo con WebView local (los recursos se empaquetan dentro del APK; no es PWA ni depende de GitHub Pages).
- Paquete previsto: `com.pdazona.explorador`
- Orientación prevista: landscape
- Ícono propio, sin símbolo de radiación.
- El APK de producción debe firmarse con una clave privada nueva y persistente, guardada como secreto de GitHub. No publicar un APK de depuración como instalador de producción.

## Cuidado del proyecto original
No editar `index.html` de la raíz, el service worker, el manifest ni los flujos de PDA madre desde esta rama. Esta carpeta se mantiene aislada.
