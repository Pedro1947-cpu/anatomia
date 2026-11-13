# Grabadora de clases

App Android personal que graba las clases (a mano o a una hora fija cada día) y sube el audio sola a una carpeta de Google Drive.

## 1. Conectar con tu Google Drive (una sola vez, unos 5 minutos)

Hazlo mejor desde el ordenador.

1. Entra en <https://script.google.com> con tu cuenta de Google y pulsa **Nuevo proyecto**.
2. Borra lo que haya en el editor y pega todo el contenido de [`drive-script/Codigo.gs`](drive-script/Codigo.gs). Arriba, cambia el nombre del proyecto a «Grabadora de clases». Guarda (icono del disquete).
3. En la barra de arriba, elige la función **autorizar** y pulsa **Ejecutar**. Google pedirá permiso:
   - **Revisar permisos** → elige tu cuenta.
   - Saldrá «Google no ha verificado esta aplicación». Es normal, porque el script es tuyo: pulsa **Configuración avanzada** → **Ir a Grabadora de clases (no seguro)** → **Permitir**.
4. Pulsa **Implementar** → **Nueva implementación**. En el engranaje elige **Aplicación web** y rellena:
   - **Ejecutar como:** Yo
   - **Quién tiene acceso:** Cualquier usuario
   - Pulsa **Implementar**.
5. Copia la **URL de la aplicación web** (empieza por `https://script.google.com/macros/s/` y acaba en `/exec`). Mándatela al móvil (por ejemplo, por WhatsApp a ti mismo).

En tu Drive aparecerá la carpeta **Grabaciones de clase**. Ahí irán los audios.

## 2. Instalar la app en el móvil

1. En el móvil, abre la página de descarga y toca **Grabadora.apk**.
2. Al abrir el archivo, Android avisará de que no viene de Play Store: toca **Ajustes** → activa **Permitir de esta fuente** → vuelve atrás → **Instalar**.
3. Si sale Play Protect, toca **Instalar de todas formas**.

## 3. Configurar la app

1. **Google Drive → Pegar enlace**: pega la URL del paso 1.5. Debe salir «✓ Conectado».
2. **Permisos → Dar el permiso que falta**: pulsa hasta que todo tenga ✓ (micrófono, notificaciones, alarmas, batería sin restricciones).
3. **Horario automático**: activa el interruptor, marca los días, la hora de inicio y cuánto dura.

Listo. A la hora marcada empieza a grabar sola (verás una notificación con el tiempo y un botón **Parar**), se para sola al acabar y sube el audio a Drive. Si no hay internet, lo sube en cuanto lo haya.

### Si es un Xiaomi, Huawei, Oppo, OnePlus o Samsung

Estas marcas cierran apps en segundo plano. Además del permiso de batería de la app:

- **Xiaomi/Redmi/POCO:** Ajustes → Apps → Grabadora de clases → **Inicio automático** activado, y **Ahorro de batería → Sin restricciones**.
- **Samsung:** Ajustes → Batería → Límites de uso en segundo plano → quita la app de «Aplicaciones en suspensión».
- **Huawei/Honor:** Ajustes → Batería → Inicio de aplicaciones → Grabadora de clases → **Administrar manualmente** con todo activado.

Haz una prueba: pon el horario a dentro de 3 minutos, bloquea el móvil y comprueba que empieza a grabar.

## Detalles técnicos

- Audio AAC mono 48 kbps (≈ 21 MB por hora).
- La grabación usa un servicio en primer plano y una alarma de tipo despertador (`setAlarmClock`), que Android respeta aunque el móvil esté en reposo.
- La subida usa la subida reanudable de Drive: si se corta, continúa donde lo dejó.
- El APK se compila en GitHub Actions (`.github/workflows/grabadora-apk.yml`) y se publica en Releases.
