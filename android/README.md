# PDA ZONA — Android companion

Módulo Android complementario basado en Trusted Web Activity (TWA), más un widget nativo de pantalla de inicio.

## Protección de datos
- La PWA publicada no se modifica desde esta rama.
- El lanzador TWA abre el sitio publicado en su origen HTTPS, evitando empaquetar una copia local de la web dentro de una WebView aislada.
- No se borra ni se migra el almacenamiento de la PWA.
- Antes de distribuir la app, hay que verificar la asociación Digital Asset Links con la huella SHA-256 del certificado de firma.

## Estado inicial
- Proyecto Android/Gradle creado.
- Lanzador TWA apuntando al sitio público actual.
- Widget nativo de acceso rápido creado.
- Workflow de GitHub Actions para generar un APK de depuración.
- El widget todavía es una carcasa de acceso rápido: no presenta clima ni alertas en vivo hasta conectar una fuente de datos y un mecanismo de actualización seguro.
- Las notificaciones Push y la actualización del widget quedan pendientes de integrar con el backend; no se solicitan ni almacenan claves privadas en el repositorio.

## Próximos pasos
1. Compilar y corregir el proyecto Android.
2. Generar una clave de firma segura y publicar assetlinks.json con su huella pública.
3. Conectar Web Push con el backend y persistir las suscripciones por dispositivo.
4. Implementar actualización del widget con datos meteorológicos reales y alertas verificadas.
5. Probar en Android 14 antes de considerar una versión instalable estable.

El APK de depuración no equivale a una app lista para distribuir y no debe reemplazar la PWA instalada hasta completar las pruebas y la validación de datos.
