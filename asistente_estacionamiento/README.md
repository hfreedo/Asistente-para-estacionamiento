# Asistente de estacionamiento Arduino — primera versión

Prototipo con Arduino UNO, HC-SR04, buzzer y tiras WS2812B. Incluye operación
real, simulación sin sensor, perfiles visuales, filtrado de distancia y guardado
manual de preferencias en EEPROM.

La versión actual está configurada para `15 LED`. La cantidad se selecciona en
el código mediante `NUM_LEDS`, y las zonas centrales se calculan automáticamente.

## Conexiones

| Elemento | Arduino / alimentación |
|---|---|
| WS2812B `DIN` | D6 mediante resistencia de 330–1000 ohm |
| WS2812B `5V` | Fuente externa regulada de 5 V |
| WS2812B `GND` | GND de fuente y GND de Arduino |
| Buzzer pasivo piezoeléctrico de bajo consumo, positivo | D3 |
| Buzzer pasivo piezoeléctrico de bajo consumo, negativo | GND |
| HC-SR04 `TRIG` | D9 |
| HC-SR04 `ECHO` | D10 |
| HC-SR04 `VCC` | 5 V Arduino |
| HC-SR04 `GND` | GND Arduino |
| HC-06 `VCC` | 5 V Arduino, solo si es módulo con regulador integrado |
| HC-06 `GND` | GND Arduino |
| HC-06 `TXD` | D7 Arduino |
| HC-06 `RXD` | D8 mediante divisor resistivo a aproximadamente 3,3 V |

Si se utiliza un módulo buzzer de tres pines, conectar `SIG` a D3, `VCC` a 5 V
y `GND` a GND. Si es un buzzer activo o magnético suelto que demanda más de
20 mA, manejarlo mediante un transistor NPN y no directamente desde D3. En el
código, cambiar `BUZZER_PASIVO` a `false` para un buzzer activo. Las tiras no deben
alimentarse desde el Arduino. El receptor remoto original debe permanecer
desconectado de la línea de datos cuando D6 la controle.

Para el RXD del HC-06 se puede formar el divisor con tres resistencias de 1 kOhm:

```text
Arduino D8 -- 1k --+--> HC-06 RXD
                   |
                  1k
                   |
                  1k
                   |
                  GND
```

El HC-06 utiliza 9600 baudios. La consola USB continúa funcionando a 115200.
El firmware acepta los mismos comandos por ambos canales y transmite telemetría
a ambos cuando `MONITOR ON` está activo.

## Modelo de distancia

Los valores iniciales son `RANGO = 120 cm` y `RANGO_MINIMO = 15 cm`. El espacio
entre ambos se divide automáticamente en tres tramos iguales:

- Fuera de rango (>120 cm): LED apagados.
- Verde: 85–120 cm.
- Amarillo: 50–85 cm.
- Rojo: 15–50 cm, buzzer intermitente.
- Crítico (<=15 cm): rojo total intermitente y buzzer rápido.

Con otros valores, los límites verde/amarillo/rojo se recalculan. El estado
crítico entra inmediatamente; los demás requieren tres clasificaciones iguales
para evitar oscilaciones. Las mediciones reales usan una mediana móvil de tres.

## Perfiles visuales

- `SIMPLE`: todos los LED cambian de color; solo el crítico parpadea.
- `GUIADO`: todos verdes, aproximadamente dos tercios amarillos centrales y un
  tercio rojo central. Con 15 LED produce 15 -> 9 -> 5 para mantener simetría;
  con 30 produce exactamente 30 -> 20 -> 10.
  La alerta crítica utiliza todos los LED. Es el perfil predeterminado y recomendado.
- `DINAMICO`: movimiento y pulsos más notorios, conservando los mismos colores
  y la alerta crítica. Dos puntos nacen en los extremos del segmento activo y
  convergen en su centro.
- `FLUJO`: construye repetidamente la señal desde el centro hacia ambos lados.
- `RESPIRACION`: ilumina suavemente cada zona con un pulso de intensidad; es
  vistoso sin generar tanto movimiento.

El estado crítico no puede suavizarse mediante el perfil porque su función es
comunicar la orden inequívoca de detenerse.

## Prueba sin HC-SR04

Abrir el Monitor Serie a 115200 baudios y ejecutar:

```text
MODO SIMULADO
DISTANCIA 130
DISTANCIA 100
DISTANCIA 70
DISTANCIA 30
DISTANCIA 10
```

Para recorrer automáticamente todas las zonas:

```text
DEMO ON
MONITOR ON
```

Detenerlo con `DEMO OFF`. Cuando se instale el sensor, usar `MODO SENSOR`.

## Configuración

```text
RANGO 150 20
PERFIL SIMPLE
PERFIL GUIADO
PERFIL DINAMICO
PERFIL FLUJO
PERFIL RESPIRACION
BRILLO 16
VELOCIDAD 5
SONIDO ON
SISTEMA ON
ESTADO
```

Los cambios actúan inmediatamente, pero solo se conservan tras reiniciar si se
ejecuta `GUARDAR`. `FABRICA` recupera los valores iniciales; después se puede
usar `GUARDAR` para hacerlos permanentes.

## Criterios de seguridad y claridad

- Verde significa aproximación permitida, amarillo precaución, rojo peligro y
  rojo total intermitente detenerse.
- El amarillo permanece sin sonido para evitar fatiga de alerta.
- El buzzer comienza en rojo y acelera en crítico.
- Cinco fallos consecutivos producen una indicación magenta de error, distinta
  de las señales normales.
- El brillo está limitado a 25/255 para las pruebas con la fuente de 5 V / 2 A.

Este es un asistente experimental, no un dispositivo automotriz certificado.
Los rangos deben calibrarse en el lugar real y nunca sustituyen la observación
del conductor.

## Cambiar entre tiras de 15 y 30 LED

Al comienzo del sketch se encuentra:

```cpp
constexpr uint16_t NUM_LEDS = 15;
```

Usar `15` o `30` según la tira y volver a cargar el programa. No es necesario
modificar ninguna animación ni mantener otro sketch.
