#include <Adafruit_NeoPixel.h>
#include <EEPROM.h>
#include <SoftwareSerial.h>
#include <ctype.h>
#include <stdlib.h>
#include <string.h>

// -------------------------- Hardware --------------------------
constexpr uint8_t PIN_LED = 6;
constexpr uint8_t PIN_BUZZER = 5;
constexpr uint8_t PIN_TRIG = 9;
constexpr uint8_t PIN_ECHO = 10;
constexpr uint8_t PIN_BT_RX = 11;  // Recibe desde TXD del HC-06.
constexpr uint8_t PIN_BT_TX = 12;  // Envia a RXD mediante divisor 5 V -> 3.3 V.
// Cambiar solamente este valor segun la tira conectada.
// El patron guiado se adapta proporcionalmente:
// 15 LED -> 15 verdes, 9 amarillos, 5 rojos (centrados simetricamente).
// 30 LED -> 30 verdes, 20 amarillos, 10 rojos.
constexpr uint16_t NUM_LEDS = 15;
constexpr uint16_t LEDS_AMARILLOS_BASE = (NUM_LEDS * 2U) / 3U;
constexpr uint16_t LEDS_ROJOS_BASE = NUM_LEDS / 3U;
// Un segmento central debe tener la misma paridad que el total para que quede
// exactamente centrado: impar con impar, par con par.
constexpr uint16_t LEDS_AMARILLOS =
    ((LEDS_AMARILLOS_BASE & 1U) == (NUM_LEDS & 1U))
        ? LEDS_AMARILLOS_BASE : LEDS_AMARILLOS_BASE - 1U;
constexpr uint16_t LEDS_ROJOS =
    ((LEDS_ROJOS_BASE & 1U) == (NUM_LEDS & 1U))
        ? LEDS_ROJOS_BASE : LEDS_ROJOS_BASE - 1U;

// true: buzzer pasivo controlado con tone().
// false: buzzer activo que suena al recibir HIGH.
constexpr bool BUZZER_PASIVO = true;
constexpr uint16_t FRECUENCIA_BUZZER = 2200;

// Proteccion inicial para la fuente de tiras de 5 V / 2 A.
constexpr uint8_t BRILLO_MAXIMO_SEGURO = 25;
constexpr uint32_t TIMEOUT_ECO_US = 25000UL;
constexpr uint16_t INTERVALO_SENSOR_MS = 65;
constexpr uint8_t FALLOS_PARA_ERROR = 5;
constexpr uint8_t LECTURAS_PARA_CAMBIO = 3;

Adafruit_NeoPixel tira(NUM_LEDS, PIN_LED, NEO_GRB + NEO_KHZ800);
SoftwareSerial bluetooth(PIN_BT_RX, PIN_BT_TX);

enum PerfilVisual : uint8_t {
  PERFIL_SIMPLE,
  PERFIL_GUIADO,
  PERFIL_DINAMICO,
  PERFIL_FLUJO,
  PERFIL_RESPIRACION
};
enum FuenteDistancia : uint8_t { FUENTE_SENSOR, FUENTE_SIMULADA };
enum EstadoDistancia : uint8_t {
  ESTADO_FUERA, ESTADO_VERDE, ESTADO_AMARILLO,
  ESTADO_ROJO, ESTADO_CRITICO, ESTADO_ERROR
};

struct Configuracion {
  uint16_t marca;
  uint8_t version;
  float rangoMaximoCm;
  float rangoMinimoCm;
  uint8_t perfil;
  uint8_t brillo;
  uint8_t velocidad;
  uint8_t sonido;
  uint16_t checksum;
};

constexpr uint16_t MARCA_CONFIG = 0xA517;
constexpr uint8_t VERSION_CONFIG = 1;

Configuracion config;
FuenteDistancia fuenteDistancia = FUENTE_SENSOR;
EstadoDistancia estadoActual = ESTADO_ERROR;
EstadoDistancia estadoCandidato = ESTADO_ERROR;

bool sistemaActivo = true;
bool demoAutomatico = false;
bool monitorSerial = false;
bool buzzerEncendido = false;

float distanciaCm = -1.0f;
float distanciaSimuladaCm = 100.0f;
float muestras[3] = {0, 0, 0};
uint8_t cantidadMuestras = 0;
uint8_t indiceMuestra = 0;
uint8_t fallosConsecutivos = 0;
uint8_t repeticionesCandidato = 0;

uint32_t ultimoSensorMs = 0;
uint32_t ultimoVisualMs = 0;
uint32_t ultimoMonitorMs = 0;
uint32_t ultimoDemoMs = 0;
uint16_t faseVisual = 0;
int8_t direccionDemo = -1;

char entrada[80];
uint8_t longitudEntrada = 0;
char entradaBluetooth[80];
uint8_t longitudBluetooth = 0;
uint32_t ultimoByteBluetoothMs = 0;
bool ledsApagados = false;
bool forzarActualizacionVisual = true;
EstadoDistancia ultimoEstadoDibujado = ESTADO_ERROR;
uint8_t ultimoPerfilDibujado = 255;

// ------------------------ Configuracion ------------------------
void valoresDeFabrica() {
  config.marca = MARCA_CONFIG;
  config.version = VERSION_CONFIG;
  config.rangoMaximoCm = 120.0f;
  config.rangoMinimoCm = 15.0f;
  config.perfil = PERFIL_GUIADO;
  config.brillo = 16;
  config.velocidad = 5;
  config.sonido = 1;
  config.checksum = 0;
}

uint16_t calcularChecksum(const Configuracion &c) {
  const uint8_t *datos = reinterpret_cast<const uint8_t *>(&c);
  uint16_t suma = 0;
  for (size_t i = 0; i < sizeof(Configuracion) - sizeof(c.checksum); ++i) {
    suma = (suma * 31U) + datos[i];
  }
  return suma;
}

bool configuracionValida(const Configuracion &c) {
  return c.marca == MARCA_CONFIG &&
         c.version == VERSION_CONFIG &&
         c.checksum == calcularChecksum(c) &&
         c.rangoMinimoCm >= 5.0f &&
         c.rangoMaximoCm <= 400.0f &&
         c.rangoMaximoCm >= c.rangoMinimoCm + 30.0f &&
         c.perfil <= PERFIL_RESPIRACION &&
         c.brillo >= 1 && c.brillo <= BRILLO_MAXIMO_SEGURO &&
         c.velocidad >= 1 && c.velocidad <= 10 &&
         c.sonido <= 1;
}

void cargarConfiguracion() {
  Configuracion almacenada;
  EEPROM.get(0, almacenada);
  if (configuracionValida(almacenada)) config = almacenada;
  else valoresDeFabrica();
}

void guardarConfiguracion() {
  config.marca = MARCA_CONFIG;
  config.version = VERSION_CONFIG;
  config.checksum = calcularChecksum(config);
  EEPROM.put(0, config);
  Serial.println(F("OK: configuracion guardada en EEPROM"));
}

// -------------------------- Distancia --------------------------
float mediana3(float a, float b, float c) {
  if (a > b) { float t = a; a = b; b = t; }
  if (b > c) { float t = b; b = c; c = t; }
  if (a > b) { float t = a; a = b; b = t; }
  return b;
}

float leerHcSr04() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(3);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);

  unsigned long duracion = pulseIn(PIN_ECHO, HIGH, TIMEOUT_ECO_US);
  if (duracion == 0) return -1.0f;

  float cm = duracion * 0.0343f / 2.0f;
  if (cm < 2.0f || cm > 400.0f) return -1.0f;
  return cm;
}

EstadoDistancia clasificarDistancia(float cm) {
  if (cm < 0) return ESTADO_ERROR;
  if (cm > config.rangoMaximoCm) return ESTADO_FUERA;
  if (cm <= config.rangoMinimoCm) return ESTADO_CRITICO;

  float tramo = config.rangoMaximoCm - config.rangoMinimoCm;
  float limiteRojo = config.rangoMinimoCm + tramo / 3.0f;
  float limiteAmarillo = config.rangoMinimoCm + 2.0f * tramo / 3.0f;

  if (cm <= limiteRojo) return ESTADO_ROJO;
  if (cm <= limiteAmarillo) return ESTADO_AMARILLO;
  return ESTADO_VERDE;
}

void proponerEstado(EstadoDistancia nuevo) {
  // El peligro critico se acepta inmediatamente; los demas cambios requieren
  // varias lecturas iguales para evitar parpadeo cerca de los limites.
  if (nuevo == ESTADO_CRITICO) {
    estadoActual = nuevo;
    estadoCandidato = nuevo;
    repeticionesCandidato = 0;
    return;
  }

  if (nuevo == estadoActual) {
    estadoCandidato = nuevo;
    repeticionesCandidato = 0;
  } else if (nuevo != estadoCandidato) {
    estadoCandidato = nuevo;
    repeticionesCandidato = 1;
  } else if (++repeticionesCandidato >= LECTURAS_PARA_CAMBIO) {
    estadoActual = nuevo;
    repeticionesCandidato = 0;
    faseVisual = 0;
  }
}

void actualizarSensor() {
  uint32_t ahora = millis();
  if (ahora - ultimoSensorMs < INTERVALO_SENSOR_MS) return;
  ultimoSensorMs = ahora;

  if (fuenteDistancia == FUENTE_SIMULADA) {
    distanciaCm = distanciaSimuladaCm;
    fallosConsecutivos = 0;
    proponerEstado(clasificarDistancia(distanciaCm));
    return;
  }

  float lectura = leerHcSr04();
  if (lectura < 0) {
    if (fallosConsecutivos < 255) ++fallosConsecutivos;
    if (fallosConsecutivos >= FALLOS_PARA_ERROR) {
      distanciaCm = -1;
      proponerEstado(ESTADO_ERROR);
    }
    return;
  }

  fallosConsecutivos = 0;
  muestras[indiceMuestra] = lectura;
  indiceMuestra = (indiceMuestra + 1) % 3;
  if (cantidadMuestras < 3) ++cantidadMuestras;

  if (cantidadMuestras == 3) distanciaCm = mediana3(muestras[0], muestras[1], muestras[2]);
  else distanciaCm = lectura;

  proponerEstado(clasificarDistancia(distanciaCm));
}

// ----------------------- Salida visual ------------------------
uint16_t intervaloVisual(uint16_t lento, uint16_t rapido) {
  return map(config.velocidad, 1, 10, lento, rapido);
}

void encenderCentro(uint16_t cantidad, uint32_t color) {
  tira.clear();
  if (cantidad > NUM_LEDS) cantidad = NUM_LEDS;
  uint16_t inicio = (NUM_LEDS - cantidad) / 2U;
  for (uint16_t i = inicio; i < inicio + cantidad; ++i) tira.setPixelColor(i, color);
}

void dibujarSimple() {
  switch (estadoActual) {
    case ESTADO_FUERA:    tira.clear(); break;
    case ESTADO_VERDE:    tira.fill(tira.Color(0, 255, 0)); break;
    case ESTADO_AMARILLO: tira.fill(tira.Color(255, 110, 0)); break;
    case ESTADO_ROJO:     tira.fill(tira.Color(255, 0, 0)); break;
    case ESTADO_CRITICO:
      if ((faseVisual / 4) % 2 == 0) tira.fill(tira.Color(255, 0, 0));
      else tira.clear();
      break;
    case ESTADO_ERROR:
      tira.clear();
      if ((faseVisual / 10) % 2 == 0) {
        tira.setPixelColor(0, tira.Color(100, 0, 100));
        tira.setPixelColor(NUM_LEDS - 1, tira.Color(100, 0, 100));
      }
      break;
  }
}

void dibujarGuiado() {
  switch (estadoActual) {
    case ESTADO_FUERA: tira.clear(); break;
    case ESTADO_VERDE: tira.fill(tira.Color(0, 255, 0)); break;
    case ESTADO_AMARILLO: encenderCentro(LEDS_AMARILLOS, tira.Color(255, 110, 0)); break;
    case ESTADO_ROJO: encenderCentro(LEDS_ROJOS, tira.Color(255, 0, 0)); break;
    case ESTADO_CRITICO:
      if ((faseVisual / 3) % 2 == 0) tira.fill(tira.Color(255, 0, 0));
      else tira.clear();
      break;
    case ESTADO_ERROR:
      tira.clear();
      if ((faseVisual / 10) % 2 == 0) {
        for (uint8_t i = 0; i < NUM_LEDS; i += 5) tira.setPixelColor(i, tira.Color(100, 0, 100));
      }
      break;
  }
}

void dibujarConvergencia(uint16_t cantidad, uint32_t color, uint32_t fondo) {
  if (cantidad > NUM_LEDS) cantidad = NUM_LEDS;
  tira.clear();
  uint16_t inicio = (NUM_LEDS - cantidad) / 2U;
  uint16_t pasos = (cantidad + 1U) / 2U;
  uint16_t paso = faseVisual % (pasos + 3U);

  // Mantiene brevemente la llegada al centro antes de reiniciar el recorrido.
  if (paso >= pasos) paso = pasos - 1U;
  for (uint16_t i = inicio; i < inicio + cantidad; ++i) tira.setPixelColor(i, fondo);

  uint16_t izquierda = inicio + paso;
  uint16_t derecha = inicio + cantidad - 1U - paso;
  tira.setPixelColor(izquierda, color);
  tira.setPixelColor(derecha, color);
}

void dibujarDinamico() {

  switch (estadoActual) {
    case ESTADO_FUERA:
      tira.clear();
      break;
    case ESTADO_VERDE:
      dibujarConvergencia(NUM_LEDS, tira.Color(0, 255, 0), tira.Color(0, 18, 0));
      break;
    case ESTADO_AMARILLO:
      dibujarConvergencia(LEDS_AMARILLOS, tira.Color(255, 100, 0), tira.Color(30, 8, 0));
      break;
    case ESTADO_ROJO:
      dibujarConvergencia(LEDS_ROJOS, tira.Color(255, 0, 0), tira.Color(35, 0, 0));
      break;
    case ESTADO_CRITICO:
      if ((faseVisual / 2) % 2 == 0) tira.fill(tira.Color(255, 0, 0));
      else tira.clear();
      break;
    case ESTADO_ERROR:
      tira.clear();
      tira.setPixelColor(faseVisual % NUM_LEDS, tira.Color(100, 0, 100));
      break;
  }
}

void dibujarFlujoCentro(uint16_t cantidad, uint32_t color) {
  if (cantidad > NUM_LEDS) cantidad = NUM_LEDS;
  tira.clear();
  uint16_t inicio = (NUM_LEDS - cantidad) / 2U;
  uint16_t pasos = (cantidad + 1U) / 2U;
  uint16_t progreso = faseVisual % (pasos + 3U);
  if (progreso > pasos) progreso = pasos;

  // Enciende pares simetricos desde el centro hacia los extremos del segmento.
  for (uint16_t i = inicio; i < inicio + cantidad; ++i) {
    int16_t distanciaDoble = abs((int16_t)(2U * i) - (int16_t)(NUM_LEDS - 1U));
    if ((uint16_t)distanciaDoble < progreso * 2U) tira.setPixelColor(i, color);
  }
}

void dibujarFlujo() {
  switch (estadoActual) {
    case ESTADO_FUERA: tira.clear(); break;
    case ESTADO_VERDE: dibujarFlujoCentro(NUM_LEDS, tira.Color(0, 255, 0)); break;
    case ESTADO_AMARILLO: dibujarFlujoCentro(LEDS_AMARILLOS, tira.Color(255, 100, 0)); break;
    case ESTADO_ROJO: dibujarFlujoCentro(LEDS_ROJOS, tira.Color(255, 0, 0)); break;
    case ESTADO_CRITICO:
      if ((faseVisual / 2) % 2 == 0) tira.fill(tira.Color(255, 0, 0));
      else tira.clear();
      break;
    case ESTADO_ERROR:
      tira.clear();
      if ((faseVisual / 8) % 2 == 0) {
        tira.setPixelColor(0, tira.Color(100, 0, 100));
        tira.setPixelColor(NUM_LEDS - 1, tira.Color(100, 0, 100));
      }
      break;
  }
}

void dibujarRespiracion() {
  uint8_t onda = faseVisual % 40;
  uint8_t intensidad = onda < 20 ? 45 + onda * 10 : 45 + (39 - onda) * 10;

  switch (estadoActual) {
    case ESTADO_FUERA: tira.clear(); break;
    case ESTADO_VERDE: encenderCentro(NUM_LEDS, tira.Color(0, intensidad, 0)); break;
    case ESTADO_AMARILLO:
      encenderCentro(LEDS_AMARILLOS, tira.Color(intensidad, intensidad / 3, 0));
      break;
    case ESTADO_ROJO: encenderCentro(LEDS_ROJOS, tira.Color(intensidad, 0, 0)); break;
    case ESTADO_CRITICO:
      if ((faseVisual / 2) % 2 == 0) tira.fill(tira.Color(255, 0, 0));
      else tira.clear();
      break;
    case ESTADO_ERROR:
      tira.clear();
      encenderCentro(1, tira.Color(intensidad / 2, 0, intensidad / 2));
      break;
  }
}

void actualizarVisual() {
  uint32_t ahora = millis();
  // Si una orden Bluetooth está entrando, terminamos de recibirla antes de
  // bloquear interrupciones con show(). Una trama parcial caduca rápidamente.
  if (longitudBluetooth > 0) {
    if (ahora - ultimoByteBluetoothMs < 100UL) return;
    longitudBluetooth = 0;
  }
  if (ahora - ultimoVisualMs < intervaloVisual(100, 30)) return;

  if (!sistemaActivo) {
    if (!ledsApagados) {
      tira.clear();
      tira.show();
      ledsApagados = true;
    }
    return;
  }

  bool perfilAnimado = config.perfil == PERFIL_DINAMICO ||
                        config.perfil == PERFIL_FLUJO ||
                        config.perfil == PERFIL_RESPIRACION;
  bool estadoAnimado = estadoActual == ESTADO_CRITICO || estadoActual == ESTADO_ERROR;
  bool contenidoCambio = forzarActualizacionVisual || ledsApagados ||
                         estadoActual != ultimoEstadoDibujado ||
                         config.perfil != ultimoPerfilDibujado;
  if (!contenidoCambio && !perfilAnimado && !estadoAnimado) return;

  ultimoVisualMs = ahora;
  ledsApagados = false;

  switch (config.perfil) {
    case PERFIL_SIMPLE: dibujarSimple(); break;
    case PERFIL_GUIADO: dibujarGuiado(); break;
    case PERFIL_DINAMICO: dibujarDinamico(); break;
    case PERFIL_FLUJO: dibujarFlujo(); break;
    case PERFIL_RESPIRACION: dibujarRespiracion(); break;
  }
  tira.show();
  ultimoEstadoDibujado = estadoActual;
  ultimoPerfilDibujado = config.perfil;
  forzarActualizacionVisual = false;
  ++faseVisual;
}

// -------------------------- Buzzer ----------------------------
void establecerBuzzer(bool encendido) {
  if (encendido == buzzerEncendido) return;
  buzzerEncendido = encendido;
  if (encendido) {
    if (BUZZER_PASIVO) tone(PIN_BUZZER, FRECUENCIA_BUZZER);
    else digitalWrite(PIN_BUZZER, HIGH);
  } else {
    if (BUZZER_PASIVO) noTone(PIN_BUZZER);
    digitalWrite(PIN_BUZZER, LOW);
  }
}

void actualizarBuzzer() {
  if (!sistemaActivo || !config.sonido || estadoActual == ESTADO_ERROR ||
      estadoActual == ESTADO_FUERA || estadoActual == ESTADO_VERDE ||
      estadoActual == ESTADO_AMARILLO) {
    establecerBuzzer(false);
    return;
  }

  uint32_t fase;
  bool sonar;
  if (estadoActual == ESTADO_CRITICO) {
    fase = millis() % 260UL;
    sonar = fase < 150;
  } else {
    fase = millis() % 750UL;
    sonar = fase < 120;
  }
  establecerBuzzer(sonar);
}

// ------------------------- Modo demo --------------------------
void actualizarDemo() {
  if (!demoAutomatico || fuenteDistancia != FUENTE_SIMULADA) return;
  uint32_t ahora = millis();
  if (ahora - ultimoDemoMs < intervaloVisual(350, 80)) return;
  ultimoDemoMs = ahora;

  distanciaSimuladaCm += direccionDemo * 2.0f;
  if (distanciaSimuladaCm <= config.rangoMinimoCm - 5.0f) direccionDemo = 1;
  if (distanciaSimuladaCm >= config.rangoMaximoCm + 15.0f) direccionDemo = -1;
}

// ----------------------- Consola serie ------------------------
const __FlashStringHelper *nombreEstado(EstadoDistancia e) {
  switch (e) {
    case ESTADO_FUERA: return F("FUERA DE RANGO");
    case ESTADO_VERDE: return F("SEGURO / VERDE");
    case ESTADO_AMARILLO: return F("PRECAUCION / AMARILLO");
    case ESTADO_ROJO: return F("PELIGRO / ROJO");
    case ESTADO_CRITICO: return F("DETENERSE / CRITICO");
    default: return F("ERROR DE SENSOR");
  }
}

void imprimirEstado() {
  Serial.print(F("Fuente: "));
  Serial.println(fuenteDistancia == FUENTE_SENSOR ? F("SENSOR") : F("SIMULADA"));
  Serial.print(F("Distancia: "));
  if (distanciaCm < 0) Serial.println(F("sin lectura"));
  else { Serial.print(distanciaCm, 1); Serial.println(F(" cm")); }
  Serial.print(F("Estado: ")); Serial.println(nombreEstado(estadoActual));
  Serial.print(F("Rango maximo/minimo: "));
  Serial.print(config.rangoMaximoCm, 1); Serial.print(F(" / "));
  Serial.print(config.rangoMinimoCm, 1); Serial.println(F(" cm"));
  Serial.print(F("Perfil: "));
  if (config.perfil == PERFIL_SIMPLE) Serial.println(F("SIMPLE"));
  else if (config.perfil == PERFIL_GUIADO) Serial.println(F("GUIADO"));
  else if (config.perfil == PERFIL_DINAMICO) Serial.println(F("DINAMICO"));
  else if (config.perfil == PERFIL_FLUJO) Serial.println(F("FLUJO"));
  else Serial.println(F("RESPIRACION"));
  Serial.print(F("Brillo/velocidad/sonido: "));
  Serial.print(config.brillo); Serial.print(F(" / "));
  Serial.print(config.velocidad); Serial.print(F(" / "));
  Serial.println(config.sonido ? F("ON") : F("OFF"));
}

void mostrarAyuda() {
  Serial.println(F("\n=== ASISTENTE DE ESTACIONAMIENTO ==="));
  Serial.println(F("MODO SENSOR             Usa el HC-SR04"));
  Serial.println(F("MODO MANUAL             Ignora el sensor y acepta DISTANCIA"));
  Serial.println(F("MODO DEMO               Recorrido automatico exclusivo"));
  Serial.println(F("DISTANCIA 75            Simula 75 cm"));
  Serial.println(F("DEMO ON|OFF             Alias compatible para la demostracion"));
  Serial.println(F("RANGO 120 15            Maximo y minimo en cm"));
  Serial.println(F("PERFIL SIMPLE|GUIADO|DINAMICO|FLUJO|RESPIRACION"));
  Serial.println(F("BRILLO 1..25            Limite para fuente 2 A"));
  Serial.println(F("VELOCIDAD 1..10         Animaciones y demo"));
  Serial.println(F("SONIDO ON|OFF"));
  Serial.println(F("SISTEMA ON|OFF"));
  Serial.println(F("MONITOR ON|OFF          Estado por USB cada 500 ms"));
  Serial.println(F("ESTADO | CONFIG | GUARDAR | FABRICA | AYUDA\n"));
}

bool esOn(const char *valor) {
  return valor && (!strcmp(valor, "ON") || !strcmp(valor, "SI") || !strcmp(valor, "1"));
}

bool esValorBooleano(const char *valor) {
  return valor && (esOn(valor) || !strcmp(valor, "OFF") || !strcmp(valor, "NO") || !strcmp(valor, "0"));
}

bool procesarComando(char *linea) {
  for (char *p = linea; *p; ++p) *p = toupper(*p);
  char *cmd = strtok(linea, " \t");
  if (!cmd) return false;

  if (!strcmp(cmd, "AYUDA") || !strcmp(cmd, "HELP")) {
    mostrarAyuda();
  } else if (!strcmp(cmd, "MODO")) {
    if (!sistemaActivo) return false;
    char *valor = strtok(nullptr, " \t");
    if (valor && !strcmp(valor, "SENSOR")) {
      fuenteDistancia = FUENTE_SENSOR;
      demoAutomatico = false;
      cantidadMuestras = fallosConsecutivos = 0;
      Serial.println(F("OK: modo sensor"));
    } else if (valor && (!strcmp(valor, "MANUAL") || !strcmp(valor, "SIMULADO") || !strcmp(valor, "TEST"))) {
      fuenteDistancia = FUENTE_SIMULADA;
      demoAutomatico = false;
      Serial.println(F("OK: modo manual"));
    } else if (valor && !strcmp(valor, "DEMO")) {
      fuenteDistancia = FUENTE_SIMULADA;
      demoAutomatico = true;
      distanciaSimuladaCm = config.rangoMaximoCm + 15.0f;
      direccionDemo = -1;
      Serial.println(F("OK: modo demo"));
    } else {
      Serial.println(F("ERROR: MODO SENSOR, MANUAL o DEMO"));
      return false;
    }
  } else if (!strcmp(cmd, "DISTANCIA") || !strcmp(cmd, "DIST")) {
    if (!sistemaActivo) return false;
    char *valor = strtok(nullptr, " \t");
    if (!valor) { Serial.println(F("ERROR: DISTANCIA centimetros")); return false; }
    distanciaSimuladaCm = constrain(atof(valor), 0.0, 450.0);
    fuenteDistancia = FUENTE_SIMULADA;
    demoAutomatico = false;
    Serial.println(F("OK: distancia simulada"));
  } else if (!strcmp(cmd, "DEMO")) {
    char *valor = strtok(nullptr, " \t");
    if (!esValorBooleano(valor)) return false;
    if (esOn(valor) && !sistemaActivo) return false;
    demoAutomatico = esOn(valor);
    if (demoAutomatico) {
      fuenteDistancia = FUENTE_SIMULADA;
      distanciaSimuladaCm = config.rangoMaximoCm + 15.0f;
      direccionDemo = -1;
    }
    Serial.println(demoAutomatico ? F("OK: demo automatica") : F("OK: demo detenida"));
  } else if (!strcmp(cmd, "RANGO")) {
    char *maximo = strtok(nullptr, " \t");
    char *minimo = strtok(nullptr, " \t");
    if (!maximo || !minimo) { Serial.println(F("ERROR: RANGO maximo minimo")); return false; }
    float nuevoMax = atof(maximo);
    float nuevoMin = atof(minimo);
    if (nuevoMin < 5 || nuevoMax > 400 || nuevoMax < nuevoMin + 30) {
      Serial.println(F("ERROR: minimo >= 5, maximo <= 400 y diferencia >= 30"));
      return false;
    }
    config.rangoMaximoCm = nuevoMax;
    config.rangoMinimoCm = nuevoMin;
    Serial.println(F("OK: rango actualizado; use GUARDAR para conservarlo"));
  } else if (!strcmp(cmd, "PERFIL")) {
    char *valor = strtok(nullptr, " \t");
    if (valor && !strcmp(valor, "SIMPLE")) config.perfil = PERFIL_SIMPLE;
    else if (valor && !strcmp(valor, "GUIADO")) config.perfil = PERFIL_GUIADO;
    else if (valor && !strcmp(valor, "DINAMICO")) config.perfil = PERFIL_DINAMICO;
    else if (valor && !strcmp(valor, "FLUJO")) config.perfil = PERFIL_FLUJO;
    else if (valor && (!strcmp(valor, "RESPIRACION") || !strcmp(valor, "PULSO"))) {
      config.perfil = PERFIL_RESPIRACION;
    } else {
      Serial.println(F("ERROR: perfil no reconocido; escriba AYUDA"));
      return false;
    }
    Serial.println(F("OK: perfil actualizado"));
  } else if (!strcmp(cmd, "BRILLO")) {
    char *valor = strtok(nullptr, " \t");
    if (!valor || atoi(valor) < 1 || atoi(valor) > BRILLO_MAXIMO_SEGURO) {
      Serial.println(F("ERROR: BRILLO 1..25")); return false;
    }
    config.brillo = atoi(valor);
    tira.setBrightness(config.brillo);
    forzarActualizacionVisual = true;
    Serial.println(F("OK: brillo actualizado"));
  } else if (!strcmp(cmd, "VELOCIDAD")) {
    char *valor = strtok(nullptr, " \t");
    if (!valor || atoi(valor) < 1 || atoi(valor) > 10) {
      Serial.println(F("ERROR: VELOCIDAD 1..10")); return false;
    }
    config.velocidad = atoi(valor);
    Serial.println(F("OK: velocidad actualizada"));
  } else if (!strcmp(cmd, "SONIDO")) {
    char *valor = strtok(nullptr, " \t");
    if (!esValorBooleano(valor)) return false;
    config.sonido = esOn(valor);
    Serial.println(config.sonido ? F("OK: sonido ON") : F("OK: sonido OFF"));
  } else if (!strcmp(cmd, "SISTEMA")) {
    char *valor = strtok(nullptr, " \t");
    if (!esValorBooleano(valor)) return false;
    sistemaActivo = esOn(valor);
    if (!sistemaActivo) demoAutomatico = false;
    forzarActualizacionVisual = true;
    if (!sistemaActivo) establecerBuzzer(false);
    Serial.println(sistemaActivo ? F("OK: sistema ON") : F("OK: sistema OFF"));
  } else if (!strcmp(cmd, "MONITOR")) {
    char *valor = strtok(nullptr, " \t");
    if (!esValorBooleano(valor)) return false;
    monitorSerial = esOn(valor);
    Serial.println(monitorSerial ? F("OK: monitor ON") : F("OK: monitor OFF"));
  } else if (!strcmp(cmd, "ESTADO") || !strcmp(cmd, "STATUS")) {
    imprimirEstado();
  } else if (!strcmp(cmd, "CONFIG")) {
    Serial.println(F("OK: configuracion solicitada"));
  } else if (!strcmp(cmd, "GUARDAR")) {
    guardarConfiguracion();
  } else if (!strcmp(cmd, "FABRICA")) {
    valoresDeFabrica();
    tira.setBrightness(config.brillo);
    forzarActualizacionVisual = true;
    Serial.println(F("OK: valores de fabrica cargados; use GUARDAR para conservarlos"));
  } else {
    Serial.println(F("Comando desconocido. Escriba AYUDA"));
    return false;
  }
  return true;
}

void imprimirConfiguracion(Print &salida) {
  salida.print(F("CONFIG RANGE="));
  salida.print(config.rangoMaximoCm, 1);
  salida.print(F(" MIN="));
  salida.print(config.rangoMinimoCm, 1);
  salida.print(F(" PROFILE="));
  salida.print(config.perfil);
  salida.print(F(" BRIGHT="));
  salida.print(config.brillo);
  salida.print(F(" SPEED="));
  salida.print(config.velocidad);
  salida.print(F(" SOUND="));
  salida.print(config.sonido);
  salida.print(F(" SYSTEM="));
  salida.print(sistemaActivo ? 1 : 0);
  salida.print(F(" MODE="));
  if (demoAutomatico) salida.println(F("DEMO"));
  else if (fuenteDistancia == FUENTE_SENSOR) salida.println(F("SENSOR"));
  else salida.println(F("MANUAL"));
}

const __FlashStringHelper *tokenEstado(EstadoDistancia e) {
  switch (e) {
    case ESTADO_FUERA: return F("FUERA");
    case ESTADO_VERDE: return F("VERDE");
    case ESTADO_AMARILLO: return F("AMARILLO");
    case ESTADO_ROJO: return F("ROJO");
    case ESTADO_CRITICO: return F("CRITICO");
    default: return F("ERROR");
  }
}

void imprimirModoRespuesta(Print &salida) {
  if (demoAutomatico) salida.print(F("DEMO"));
  else if (fuenteDistancia == FUENTE_SENSOR) salida.print(F("SENSOR"));
  else salida.print(F("MANUAL"));
}

void imprimirRespuestaBluetooth(uint16_t id, bool aceptado, bool incluirConfiguracion) {
  bluetooth.print(F("RSP "));
  bluetooth.print(id);
  bluetooth.print(aceptado ? F(" OK") : F(" ERR"));
  if (!aceptado) bluetooth.print(F(" REASON=INVALID"));
  if (incluirConfiguracion) {
    bluetooth.print(F(" RANGE=")); bluetooth.print(config.rangoMaximoCm, 1);
    bluetooth.print(F(" MIN=")); bluetooth.print(config.rangoMinimoCm, 1);
    bluetooth.print(F(" PROFILE=")); bluetooth.print(config.perfil);
    bluetooth.print(F(" BRIGHT=")); bluetooth.print(config.brillo);
    bluetooth.print(F(" SPEED=")); bluetooth.print(config.velocidad);
    bluetooth.print(F(" SOUND=")); bluetooth.print(config.sonido);
  }
  bluetooth.print(F(" SYSTEM=")); bluetooth.print(sistemaActivo ? 1 : 0);
  bluetooth.print(F(" MODE=")); imprimirModoRespuesta(bluetooth);
  bluetooth.print(F(" DIST="));
  if (distanciaCm < 0) bluetooth.print(F("ERROR"));
  else bluetooth.print(distanciaCm, 1);
  bluetooth.print(F(" STATE="));
  bluetooth.println(tokenEstado(estadoActual));
}

bool esConsultaEstado(const char *comando) {
  while (*comando == ' ' || *comando == '\t') ++comando;
  char token[8];
  uint8_t i = 0;
  while (*comando && *comando != ' ' && *comando != '\t' && i < sizeof(token) - 1) {
    token[i++] = toupper(*comando++);
  }
  token[i] = '\0';
  return !strcmp(token, "ESTADO") || !strcmp(token, "STATUS");
}

bool extraerTramaBluetooth(char *buffer, uint16_t &id, char *&comando) {
  if (buffer[0] != '@') return false;
  char *separador = strchr(buffer, ' ');
  if (!separador) return false;
  *separador = '\0';
  char *fin = nullptr;
  unsigned long valor = strtoul(buffer + 1, &fin, 10);
  if (!fin || *fin != '\0' || valor == 0 || valor > 9999) return false;
  comando = separador + 1;
  while (*comando == ' ' || *comando == '\t') ++comando;
  if (!*comando) return false;
  id = (uint16_t)valor;
  return true;
}

void leerCanal(Stream &canal, char *buffer, uint8_t &longitud, bool esBluetooth) {
  while (canal.available()) {
    char c = canal.read();
    if (esBluetooth) ultimoByteBluetoothMs = millis();
    if (c == '\r' || c == '\n') {
      if (longitud > 0) {
        buffer[longitud] = '\0';
        char *comando = buffer;
        uint16_t id = 0;
        bool enmarcado = esBluetooth && buffer[0] == '@';
        bool tramaValida = !enmarcado || extraerTramaBluetooth(buffer, id, comando);
        bool consultaEstado = tramaValida && esConsultaEstado(comando);
        bool aceptado = tramaValida && procesarComando(comando);
        longitud = 0;
        if (esBluetooth) {
          if (enmarcado) imprimirRespuestaBluetooth(id, aceptado, !consultaEstado);
          else {
            bluetooth.println(aceptado ? F("OK") : F("ERR"));
            imprimirConfiguracion(bluetooth);
          }
        }
      }
    } else if (longitud < 79) {
      buffer[longitud++] = c;
    }
  }
}

void leerConsolas() {
  leerCanal(Serial, entrada, longitudEntrada, false);
  leerCanal(bluetooth, entradaBluetooth, longitudBluetooth, true);
}

void imprimirTelemetria(Print &salida) {
  salida.print(F("DIST="));
  if (distanciaCm < 0) salida.print(F("ERROR"));
  else salida.print(distanciaCm, 1);
  salida.print(F(" cm | "));
  salida.println(nombreEstado(estadoActual));
}

void actualizarMonitor() {
  if (!monitorSerial || millis() - ultimoMonitorMs < 500) return;
  ultimoMonitorMs = millis();
  imprimirTelemetria(Serial);
}

// ------------------------- Inicio -----------------------------
void setup() {
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_TRIG, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  cargarConfiguracion();
  tira.begin();
  tira.setBrightness(config.brillo);
  tira.clear();
  tira.show();

  Serial.begin(115200);
  bluetooth.begin(9600);
  delay(300);
  Serial.println(F("Asistente de estacionamiento listo."));
  Serial.println(F("Sin HC-SR04: use MODO MANUAL y DISTANCIA 100, o MODO DEMO."));
  mostrarAyuda();
  bluetooth.println(F("READY ParkAssist"));
}

void loop() {
  leerConsolas();
  actualizarDemo();
  actualizarSensor();
  actualizarVisual();
  actualizarBuzzer();
  actualizarMonitor();
}
