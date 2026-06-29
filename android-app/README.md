# ParkAssist para Android

Aplicación Android nativa en Kotlin para supervisar y configurar el asistente de
estacionamiento Arduino mediante un HC-06 (Bluetooth clásico SPP).

Diseño y desarrollo: [@hfreedo](https://github.com/hfreedo).

## Funciones incluidas

- Selección de cualquier dispositivo Bluetooth previamente emparejado.
- Conexión RFCOMM con el UUID SPP estándar.
- Distancia, estado y representación de los 15 LED en tiempo real.
- Perfiles SIMPLE, GUIADO, DINAMICO, FLUJO y RESPIRACION.
- Configuración de rango, brillo, velocidad y sonido.
- Modos exclusivos Sensor, Manual y Demo, sin combinaciones ambiguas.
- Encendido y pausa del sistema desde la pantalla principal.
- Reconexión automática escalonada al último HC-06 seleccionado.
- Cola de un solo comando, confirmaciones numeradas y un reintento controlado.
- Consulta de estado bajo demanda, sin telemetría Bluetooth espontánea.
- Guardado en EEPROM mediante el comando `GUARDAR`.
- Registro de comandos y respuestas.
- Registro de altura fija con desplazamiento interno y acción para limpiarlo.
- Navegación inferior con destinos independientes de Monitor, Calibración y Ajustes.
- Cajón lateral con dispositivo, diagnóstico, FAQ y Acerca del proyecto.
- Sincronización de rangos, perfil, sonido, encendido y modo mediante respuestas `RSP` del Arduino.

## Abrir el proyecto

1. Instalar Android Studio con el SDK recomendado y JDK 17.
2. Abrir la carpeta `android-app`.
3. Aceptar la sincronización de Gradle; si Android Studio ofrece actualizar el
   plugin, se puede aceptar la migración automática.
4. Emparejar el HC-06 desde Ajustes de Android (PIN habitual 1234 o 0000).
5. Ejecutar la app en un teléfono físico con Android 6 o posterior.
6. Conceder el permiso de dispositivos cercanos en Android 12 o posterior.

La conexión Bluetooth debe validarse en un teléfono real. El mockup navegable
está en `mockup/index.html` y no controla hardware.

## Protocolo

La app encapsula cada orden con un identificador y nunca mantiene dos operaciones
pendientes al mismo tiempo. Por ejemplo:

```text
@17 SISTEMA OFF
RSP 17 OK RANGE=120.0 MIN=15.0 PROFILE=1 BRIGHT=16 SPEED=5 SOUND=1 SYSTEM=0 MODE=SENSOR DIST=68.0 STATE=AMARILLO
```

Cuando la cola está libre, la app solicita una lectura compacta:

```text
@18 ESTADO
RSP 18 OK SYSTEM=0 MODE=SENSOR DIST=68.0 STATE=AMARILLO
```

Los comandos sin identificador continúan disponibles desde el Monitor Serie para
pruebas manuales. La aplicación reintenta una vez ante corrupción o timeout, pero
no reproduce órdenes antiguas después de una desconexión.

## Distribución

Nunca subir archivos `*.jks`, `*.keystore`, contraseñas ni `keystore.properties`
al repositorio. Para distribución, generar una APK release firmada y adjuntarla
a una publicación de GitHub Releases.
