# Prueba de tiras WS2812B por consola serie

## Conexiones

- Arduino `D6` -> resistencia de `1 kOhm` -> `DIN/DATA` de la placa distribuidora.
- GND del Arduino -> GND de la fuente y de las tiras.
- Fuente externa `5 V / 2 A` -> `5V` y `GND` de la placa distribuidora.
- Arduino alimentado inicialmente mediante USB.
- El receptor remoto original debe quedar desconectado de `DATA` mientras D6 controle las tiras.

No alimentar las tiras desde el pin de 5 V del Arduino.

## Preparacion

1. Instalar la libreria **Adafruit NeoPixel** desde el gestor de bibliotecas del Arduino IDE.
2. Abrir `test_tiras_ws2812b.ino`.
3. Seleccionar **Arduino UNO** y el puerto correspondiente.
4. Cargar el programa.
5. Abrir el Monitor Serie a **115200 baudios**.
6. Escribir `AYUDA` y enviar.

El programa acepta cualquier opcion de terminacion de linea del Monitor Serie.

## Comandos principales

```text
ROJO
VERDE
AZUL
AMARILLO
BLANCO
COLOR 120 0 255
ARCOIRIS
PERSECUCION
BARRIDO
PULSO
PARPADEO
COMETA
POLICIA
TEST
BRILLO 10
VELOCIDAD 7
APAGAR
ESTADO
AYUDA
```

El brillo esta limitado deliberadamente a `25/255` para realizar las primeras
pruebas con una fuente de 5 V y 2 A. El encendido en blanco tambien respeta ese
limite.

`VELOCIDAD` acepta valores entre 1 y 10 y modifica inmediatamente cualquier
animacion activa. El valor 1 es el mas lento y 10 el mas rapido.

## Numero de LED

El sketch usa `NUM_LEDS = 15`, correspondiente a la tira de prueba actual. Si las
ramas de la placa distribuidora están en paralelo, todas repiten el mismo patrón.
Si los LED estan realmente
conectados como una sola cadena, cambiar `NUM_LEDS` por el total encadenado.
