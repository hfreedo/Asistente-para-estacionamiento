<p align="center">
  <img src="android-app/assets/parkassist-logo.png" width="150" alt="Logo de ParkAssist">
</p>

<h1 align="center">ParkAssist</h1>

<p align="center">
  Prototipo de asistencia al estacionamiento con Arduino UNO, sensor ultrasónico,
  alerta sonora, tiras LED direccionables y control desde Android.
</p>

<p align="center">
  Desarrollado por <a href="https://github.com/hfreedo">@hfreedo</a>
</p>

## Descripción

ParkAssist es un prototipo electrónico que representa la distancia entre un
vehículo y un obstáculo mediante señales visuales y sonoras. El Arduino procesa
las mediciones del HC-SR04, clasifica la distancia en zonas de seguridad y
controla una tira WS2812B junto con un buzzer.

La aplicación Android permite supervisar el estado, configurar rangos y probar
el sistema mediante Bluetooth clásico con un módulo HC-06. El funcionamiento de
seguridad permanece en el Arduino: una desconexión del teléfono no debe detener
la medición ni las alertas locales.

> [!WARNING]
> Este proyecto es un prototipo experimental y educativo. No es un sistema
> automotriz certificado y no sustituye la observación ni el criterio del
> conductor.

## Estado del proyecto

- Firmware principal para Arduino: implementado.
- Perfiles visuales y alertas sonoras: implementados.
- Simulación sin HC-SR04: implementada.
- Configuración persistente mediante EEPROM: implementada.
- Comunicación USB y HC-06: implementada en el firmware.
- Aplicación Android: primera versión compilable y en revisión.
- Validación integral con vehículo, sensor y montaje definitivo: pendiente.

## Características

- Medición de distancia mediante HC-SR04.
- Filtrado por mediana de tres muestras.
- Confirmación de lecturas para reducir cambios inestables entre zonas.
- Entrada inmediata al estado crítico.
- Tira WS2812B configurable para 15 o 30 LED.
- Cinco perfiles visuales: Simple, Guiado, Dinámico, Flujo y Respiración.
- Buzzer intermitente en peligro y rápido en estado crítico.
- Detección visual de error del sensor.
- Modo simulado y demostración automática.
- Configuración mediante consola USB o Bluetooth.
- Persistencia manual de parámetros en EEPROM.
- Aplicación Android nativa escrita en Kotlin.

## Comportamiento visual predeterminado

La configuración inicial utiliza un rango máximo de `120 cm`, un rango mínimo
de `15 cm` y una tira de 15 LED.

| Distancia | Estado | Tira LED | Buzzer |
|---:|---|---|---|
| Más de 120 cm | Fuera de rango | Apagada | Apagado |
| 85–120 cm | Seguro | 15 LED verdes | Apagado |
| 50–85 cm | Precaución | 9 LED amarillos centrales | Apagado |
| 15–50 cm | Peligro | 5 LED rojos centrales | Intermitente |
| 15 cm o menos | Detenerse | 15 LED rojos intermitentes | Rápido |

Los límites intermedios se recalculan en el firmware cuando se modifican el
rango máximo y el mínimo.

## Componentes

### Indispensables

- Arduino UNO.
- Sensor ultrasónico HC-SR04.
- Tira direccionable WS2812B de 5 V.
- Buzzer pasivo de bajo consumo o módulo buzzer.
- Fuente regulada de 5 V con corriente suficiente.
- Resistencia de 330–1000 ohm para `DIN`.
- Condensador electrolítico de aproximadamente 1000 µF.
- Cables y GND común.

### Opcionales

- HC-06 para configuración y supervisión desde Android.
- Transistor NPN para un buzzer que consuma más de 20 mA.
- Fusible y distribución de alimentación dedicada para múltiples tiras.

## Conexiones

| Dispositivo | Conexión |
|---|---|
| WS2812B `DIN` | Arduino D6 mediante resistencia |
| WS2812B `5V` | Fuente externa regulada de 5 V |
| WS2812B `GND` | GND de fuente y Arduino |
| Buzzer pasivo | D3 y GND |
| HC-06 `TXD` | Arduino D7 |
| HC-06 `RXD` | Arduino D8 mediante divisor a 3,3 V |
| HC-SR04 `TRIG` | Arduino D9 |
| HC-SR04 `ECHO` | Arduino D10 |
| HC-SR04 `VCC/GND` | 5 V y GND |

Divisor sugerido para proteger el RXD del HC-06:

```text
Arduino D8 ---- 1 kΩ ----+---- HC-06 RXD
                         |
                        1 kΩ
                         |
                        1 kΩ
                         |
                        GND
```

> [!CAUTION]
> No se deben conectar 6 V directamente a las tiras WS2812B. Para varias tiras,
> la corriente no debe circular a través del Arduino ni de una protoboard.

## Perfiles visuales

- **Simple:** cambios de color sin movimiento, salvo la alerta crítica.
- **Guiado:** reduce la zona iluminada de 15 a 9 y finalmente a 5 LED.
- **Dinámico:** señales simétricas convergen desde los extremos hacia el centro.
- **Flujo:** construye la iluminación desde el centro hacia ambos lados.
- **Respiración:** modifica suavemente la intensidad de la zona activa.

Todos los perfiles conservan el rojo total intermitente para el estado crítico.

## Instalación del firmware

1. Instalar Arduino IDE.
2. Instalar `Adafruit NeoPixel` desde el administrador de bibliotecas.
3. Abrir
   [`asistente_estacionamiento.ino`](asistente_estacionamiento/asistente_estacionamiento.ino).
4. Seleccionar la placa Arduino UNO y su puerto.
5. Verificar y cargar el programa.
6. Abrir el Monitor Serie a `115200` baudios.

El HC-06 utiliza `9600` baudios. Los comandos enviados por USB o Bluetooth son
líneas de texto ASCII.

## Prueba sin sensor ultrasónico

```text
MODO SIMULADO
PERFIL GUIADO
DISTANCIA 130
DISTANCIA 100
DISTANCIA 70
DISTANCIA 30
DISTANCIA 10
```

Demostración automática:

```text
DEMO ON
MONITOR ON
```

Para regresar al HC-SR04:

```text
DEMO OFF
MODO SENSOR
```

## Comandos principales

```text
MODO SENSOR
MODO SIMULADO
DISTANCIA 68
DEMO ON
DEMO OFF
RANGO 120 15
PERFIL SIMPLE
PERFIL GUIADO
PERFIL DINAMICO
PERFIL FLUJO
PERFIL RESPIRACION
BRILLO 16
VELOCIDAD 5
SONIDO ON
SISTEMA ON
MONITOR ON
ESTADO
GUARDAR
FABRICA
AYUDA
```

`GUARDAR` escribe las preferencias en EEPROM. Sin este comando, los cambios se
aplican durante la sesión pero pueden perderse al reiniciar.

## Aplicación Android

La app se encuentra en [`android-app`](android-app) y utiliza Bluetooth clásico
SPP/RFCOMM para comunicarse con el HC-06.

### Requisitos

- Android Studio.
- JDK 17 incluido en Android Studio.
- Android SDK 35.
- Teléfono físico con Android 6 o posterior.
- Permiso de dispositivos cercanos en Android 12 o posterior.
- HC-06 emparejado previamente desde los ajustes de Android.

### Compilar

1. Abrir la carpeta `android-app` en Android Studio.
2. Esperar la sincronización de Gradle.
3. Ejecutar `Assemble 'app' Run Configuration` o `Ctrl + F9`.
4. Conectar un teléfono con depuración USB o inalámbrica.
5. Pulsar `Run`.

La APK de depuración se genera localmente en:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Los archivos generados dentro de `build/` no deben incluirse en Git.

## Estructura del repositorio

```text
.
├── asistente_estacionamiento/   Firmware principal
├── test_tiras_ws2812b/          Pruebas de colores y animaciones
├── android-app/                 Aplicación Android y mockup
├── LICENSE                      Licencia MIT del proyecto
└── THIRD_PARTY_NOTICES.md       Dependencias y licencias externas
```

## Limitaciones conocidas

- El HC-SR04 puede producir errores con superficies inclinadas, humedad,
  vibraciones o materiales que reflejen mal el ultrasonido.
- La aplicación Android todavía se encuentra en revisión de navegación,
  permisos, accesibilidad y sincronización de rangos.
- La representación de distancia debe verificarse contra la configuración real
  del firmware antes de una publicación estable.
- El HC-06 utiliza un PIN básico y no proporciona seguridad moderna.
- El montaje todavía requiere calibración física en el lugar de instalación.

## Próximas mejoras

- Asistente de calibración guiada.
- Sincronización completa de rangos entre Arduino y Android.
- Reconexión automática al último HC-06.
- Pantallas independientes de monitor, calibración y ajustes.
- Diagnóstico de sensor, Bluetooth y firmware.
- Corrección de hallazgos de Android Lint y mejora de accesibilidad.
- Pruebas físicas documentadas con diferentes superficies y distancias.

## Seguridad

- Desconectar la alimentación antes de modificar el cableado.
- Compartir GND entre Arduino, sensor, HC-06, buzzer y fuente de las tiras.
- No alimentar múltiples tiras desde el pin de 5 V del Arduino.
- Utilizar transistor para cargas que excedan la corriente segura de un pin.
- Probar primero con brillo reducido y una sola tira.
- No confiar exclusivamente en este prototipo para evitar una colisión.

## Licencia

El código propio de ParkAssist se distribuye bajo la [Licencia MIT](LICENSE),
copyright © 2026 hfreedo.

Las bibliotecas externas conservan sus propias licencias. Consulta
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Autor

**hfreedo** — [github.com/hfreedo](https://github.com/hfreedo)

