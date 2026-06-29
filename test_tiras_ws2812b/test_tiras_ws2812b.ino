#include <Adafruit_NeoPixel.h>
#include <ctype.h>
#include <stdlib.h>
#include <string.h>

// Conexion: Arduino D6 -> resistencia de 1 kOhm -> DIN de la placa/tira.
// La fuente de las tiras y el Arduino deben compartir GND.
constexpr uint8_t PIN_LED = 6;

// Ajustado a la tira de prueba actual de 15 LED.
// Si forman una sola cadena, colocar aqui el total de LED encadenados.
constexpr uint16_t NUM_LEDS = 15;

// Limite conservador para probar muchas tiras con una fuente de 5 V / 2 A.
// 25/255 equivale aproximadamente al 10 % del brillo digital maximo.
constexpr uint8_t BRILLO_INICIAL = 16;
constexpr uint8_t BRILLO_MAXIMO_SEGURO = 25;

Adafruit_NeoPixel tira(NUM_LEDS, PIN_LED, NEO_GRB + NEO_KHZ800);

enum Modo : uint8_t {
  APAGADO, FIJO, ARCOIRIS, PERSECUCION, BARRIDO,
  PULSO, PARPADEO, COMETA, POLICIA, DEMOSTRACION
};

Modo modo = APAGADO;
uint8_t brillo = BRILLO_INICIAL;
uint8_t velocidad = 5;  // 1 = muy lenta, 10 = muy rapida
uint32_t colorActual = 0;
uint16_t pasoAnimacion = 0;
uint32_t ultimoCambio = 0;
int16_t posicionCometa = 0;
int8_t direccionCometa = 1;

char entrada[64];
uint8_t longitudEntrada = 0;

void mostrarAyuda();
void procesarComando(char *linea);
void actualizarAnimacion();

// Convierte la velocidad 1..10 en un intervalo entre lento y rapido.
uint16_t intervalo(uint16_t lento, uint16_t rapido) {
  return map(velocidad, 1, 10, lento, rapido);
}

void apagar() {
  tira.clear();
  tira.show();
}

void llenar(uint32_t color) {
  tira.fill(color, 0, NUM_LEDS);
  tira.show();
}

void seleccionarColor(uint8_t rojo, uint8_t verde, uint8_t azul) {
  colorActual = tira.Color(rojo, verde, azul);
  modo = FIJO;
  llenar(colorActual);
}

void imprimirEstado() {
  Serial.print(F("LED logicos: "));
  Serial.println(NUM_LEDS);
  Serial.print(F("Brillo: "));
  Serial.print(brillo);
  Serial.print('/');
  Serial.println(BRILLO_MAXIMO_SEGURO);
  Serial.print(F("Modo: "));
  switch (modo) {
    case APAGADO:      Serial.println(F("APAGADO")); break;
    case FIJO:         Serial.println(F("COLOR FIJO")); break;
    case ARCOIRIS:     Serial.println(F("ARCOIRIS")); break;
    case PERSECUCION:  Serial.println(F("PERSECUCION")); break;
    case BARRIDO:      Serial.println(F("BARRIDO")); break;
    case PULSO:        Serial.println(F("PULSO")); break;
    case PARPADEO:     Serial.println(F("PARPADEO")); break;
    case COMETA:       Serial.println(F("COMETA")); break;
    case POLICIA:      Serial.println(F("POLICIA")); break;
    case DEMOSTRACION: Serial.println(F("TEST")); break;
  }
  Serial.print(F("Velocidad: "));
  Serial.println(velocidad);
}

void iniciarModo(Modo nuevoModo) {
  modo = nuevoModo;
  pasoAnimacion = 0;
  ultimoCambio = 0;
  posicionCometa = 0;
  direccionCometa = 1;
  tira.clear();
  tira.show();
}

void procesarComando(char *linea) {
  char *comando = strtok(linea, " \t");
  if (comando == nullptr) return;

  for (char *p = comando; *p; ++p) *p = toupper(*p);

  if (!strcmp(comando, "HELP") || !strcmp(comando, "AYUDA")) {
    mostrarAyuda();
  } else if (!strcmp(comando, "OFF") || !strcmp(comando, "APAGAR")) {
    modo = APAGADO;
    apagar();
    Serial.println(F("OK: tiras apagadas"));
  } else if (!strcmp(comando, "ROJO") || !strcmp(comando, "RED")) {
    seleccionarColor(255, 0, 0);
    Serial.println(F("OK: rojo"));
  } else if (!strcmp(comando, "VERDE") || !strcmp(comando, "GREEN")) {
    seleccionarColor(0, 255, 0);
    Serial.println(F("OK: verde"));
  } else if (!strcmp(comando, "AZUL") || !strcmp(comando, "BLUE")) {
    seleccionarColor(0, 0, 255);
    Serial.println(F("OK: azul"));
  } else if (!strcmp(comando, "AMARILLO")) {
    seleccionarColor(255, 100, 0);
    Serial.println(F("OK: amarillo"));
  } else if (!strcmp(comando, "BLANCO") || !strcmp(comando, "WHITE")) {
    seleccionarColor(255, 255, 255);
    Serial.println(F("OK: blanco con brillo limitado"));
  } else if (!strcmp(comando, "COLOR")) {
    char *r = strtok(nullptr, " \t");
    char *g = strtok(nullptr, " \t");
    char *b = strtok(nullptr, " \t");
    if (!r || !g || !b) {
      Serial.println(F("ERROR: use COLOR R G B, valores entre 0 y 255"));
      return;
    }
    long rv = constrain(atol(r), 0L, 255L);
    long gv = constrain(atol(g), 0L, 255L);
    long bv = constrain(atol(b), 0L, 255L);
    seleccionarColor((uint8_t)rv, (uint8_t)gv, (uint8_t)bv);
    Serial.println(F("OK: color personalizado"));
  } else if (!strcmp(comando, "BRILLO")) {
    char *valor = strtok(nullptr, " \t");
    if (!valor) {
      Serial.println(F("ERROR: use BRILLO 1..25"));
      return;
    }
    brillo = constrain(atol(valor), 1L, (long)BRILLO_MAXIMO_SEGURO);
    tira.setBrightness(brillo);
    tira.show();
    Serial.print(F("OK: brillo = "));
    Serial.println(brillo);
  } else if (!strcmp(comando, "VELOCIDAD") || !strcmp(comando, "SPEED")) {
    char *valor = strtok(nullptr, " \t");
    if (!valor) {
      Serial.println(F("ERROR: use VELOCIDAD 1..10"));
      return;
    }
    velocidad = constrain(atol(valor), 1L, 10L);
    Serial.print(F("OK: velocidad = "));
    Serial.println(velocidad);
  } else if (!strcmp(comando, "ARCOIRIS")) {
    iniciarModo(ARCOIRIS);
    Serial.println(F("OK: arcoiris"));
  } else if (!strcmp(comando, "CHASE") || !strcmp(comando, "PERSECUCION")) {
    iniciarModo(PERSECUCION);
    Serial.println(F("OK: persecucion"));
  } else if (!strcmp(comando, "WIPE") || !strcmp(comando, "BARRIDO")) {
    colorActual = tira.Color(0, 255, 0);
    iniciarModo(BARRIDO);
    Serial.println(F("OK: barrido"));
  } else if (!strcmp(comando, "PULSO") || !strcmp(comando, "PULSE")) {
    iniciarModo(PULSO);
    Serial.println(F("OK: pulso naranja"));
  } else if (!strcmp(comando, "PARPADEO") || !strcmp(comando, "BLINK")) {
    iniciarModo(PARPADEO);
    Serial.println(F("OK: parpadeo rojo"));
  } else if (!strcmp(comando, "COMETA") || !strcmp(comando, "COMET")) {
    iniciarModo(COMETA);
    Serial.println(F("OK: cometa con rebote"));
  } else if (!strcmp(comando, "POLICIA") || !strcmp(comando, "POLICE")) {
    iniciarModo(POLICIA);
    Serial.println(F("OK: patron rojo y azul"));
  } else if (!strcmp(comando, "TEST")) {
    iniciarModo(DEMOSTRACION);
    Serial.println(F("OK: demostracion automatica"));
  } else if (!strcmp(comando, "ESTADO") || !strcmp(comando, "STATUS")) {
    imprimirEstado();
  } else {
    Serial.println(F("Comando desconocido. Escriba AYUDA"));
  }
}

void leerConsola() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r' || c == '\n') {
      if (longitudEntrada > 0) {
        entrada[longitudEntrada] = '\0';
        procesarComando(entrada);
        longitudEntrada = 0;
      }
    } else if (longitudEntrada < sizeof(entrada) - 1) {
      entrada[longitudEntrada++] = c;
    }
  }
}

void actualizarAnimacion() {
  const uint32_t ahora = millis();

  if (modo == ARCOIRIS && ahora - ultimoCambio >= intervalo(110, 15)) {
    ultimoCambio = ahora;
    for (uint16_t i = 0; i < NUM_LEDS; ++i) {
      uint16_t tono = pasoAnimacion * 256UL + (uint32_t)i * 65536UL / NUM_LEDS;
      tira.setPixelColor(i, tira.gamma32(tira.ColorHSV(tono)));
    }
    tira.show();
    ++pasoAnimacion;
  } else if (modo == PERSECUCION && ahora - ultimoCambio >= intervalo(280, 35)) {
    ultimoCambio = ahora;
    tira.clear();
    for (uint16_t i = pasoAnimacion % 3; i < NUM_LEDS; i += 3) {
      tira.setPixelColor(i, tira.Color(255, 40, 0));
    }
    tira.show();
    pasoAnimacion = (pasoAnimacion + 1) % 3;
  } else if (modo == BARRIDO && ahora - ultimoCambio >= intervalo(300, 25)) {
    ultimoCambio = ahora;
    if (pasoAnimacion == 0) tira.clear();
    tira.setPixelColor(pasoAnimacion, colorActual);
    tira.show();
    pasoAnimacion = (pasoAnimacion + 1) % NUM_LEDS;
  } else if (modo == PULSO && ahora - ultimoCambio >= intervalo(90, 20)) {
    ultimoCambio = ahora;
    // Onda triangular de 10 % a 100 %, sin alterar el limite global de brillo.
    uint8_t fase = pasoAnimacion % 100;
    uint8_t intensidad = fase < 50 ? 26 + fase * 4 : 26 + (99 - fase) * 4;
    llenar(tira.Color(intensidad, intensidad / 4, 0));
    pasoAnimacion = (pasoAnimacion + 1) % 100;
  } else if (modo == PARPADEO && ahora - ultimoCambio >= intervalo(900, 100)) {
    ultimoCambio = ahora;
    if (pasoAnimacion % 2 == 0) llenar(tira.Color(255, 0, 0));
    else apagar();
    ++pasoAnimacion;
  } else if (modo == COMETA && ahora - ultimoCambio >= intervalo(220, 25)) {
    ultimoCambio = ahora;
    tira.clear();
    // Cabeza y una cola de cuatro LED con intensidad decreciente.
    for (uint8_t cola = 0; cola < 5; ++cola) {
      int16_t indice = posicionCometa - direccionCometa * cola;
      if (indice >= 0 && indice < NUM_LEDS) {
        uint8_t intensidad = 255 / (cola + 1);
        tira.setPixelColor(indice, tira.Color(0, intensidad / 2, intensidad));
      }
    }
    tira.show();
    posicionCometa += direccionCometa;
    if (posicionCometa >= NUM_LEDS - 1) {
      posicionCometa = NUM_LEDS - 1;
      direccionCometa = -1;
    } else if (posicionCometa <= 0) {
      posicionCometa = 0;
      direccionCometa = 1;
    }
  } else if (modo == POLICIA && ahora - ultimoCambio >= intervalo(450, 70)) {
    ultimoCambio = ahora;
    uint16_t mitad = NUM_LEDS / 2;
    for (uint16_t i = 0; i < NUM_LEDS; ++i) {
      bool ladoIzquierdo = i < mitad;
      bool faseRoja = pasoAnimacion % 2 == 0;
      tira.setPixelColor(i, ladoIzquierdo == faseRoja
                              ? tira.Color(255, 0, 0)
                              : tira.Color(0, 0, 255));
    }
    tira.show();
    ++pasoAnimacion;
  } else if (modo == DEMOSTRACION && ahora - ultimoCambio >= intervalo(2500, 450)) {
    ultimoCambio = ahora;
    switch (pasoAnimacion % 5) {
      case 0: llenar(tira.Color(255, 0, 0)); break;
      case 1: llenar(tira.Color(0, 255, 0)); break;
      case 2: llenar(tira.Color(0, 0, 255)); break;
      case 3: llenar(tira.Color(255, 100, 0)); break;
      case 4: apagar(); break;
    }
    ++pasoAnimacion;
  }
}

void mostrarAyuda() {
  Serial.println(F("\nComandos disponibles:"));
  Serial.println(F("  ROJO | VERDE | AZUL | AMARILLO | BLANCO"));
  Serial.println(F("  COLOR R G B       Ejemplo: COLOR 120 0 255"));
  Serial.println(F("  ARCOIRIS | PERSECUCION | BARRIDO | PULSO"));
  Serial.println(F("  PARPADEO | COMETA | POLICIA | TEST"));
  Serial.println(F("  BRILLO N           Rango seguro: 1..25"));
  Serial.println(F("  VELOCIDAD N        1 = lenta, 10 = rapida"));
  Serial.println(F("  APAGAR | ESTADO | AYUDA"));
  Serial.println(F("Use 115200 baudios y cualquier terminacion de linea.\n"));
}

void setup() {
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, LOW);

  tira.begin();
  tira.setBrightness(brillo);
  apagar();

  Serial.begin(115200);
  delay(300);
  Serial.println(F("Control de prueba WS2812B listo. LED apagados."));
  mostrarAyuda();
}

void loop() {
  leerConsola();
  actualizarAnimacion();
}
