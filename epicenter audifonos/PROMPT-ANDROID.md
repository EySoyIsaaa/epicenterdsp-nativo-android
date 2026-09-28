# Prompt: portar el "Modo Audífonos" del Epicenter a Android

Copia este documento completo como instrucción inicial para el agente/desarrollador que hará el port.

---

## Contexto del proyecto

App: **EpicenterDSP Player** (`com.epicenter.hifi`), hecha con **Capacitor** (React + TypeScript en el WebView, código nativo por plataforma). El usuario importa sus propios archivos de audio locales; no hay streaming.

El producto vende un procesador de graves llamado **Epicenter**, inspirado en la patente US 4,698,842 (AudioControl): detecta el fundamental del bajo y sintetiza el subarmónico perdido.

En **iOS** existen ahora **dos motores** de Epicenter, seleccionables con un switch:

| Modo | Motor | Para qué |
|---|---|---|
| 🚗 **Car Audio** | `EpicenterDSPCore` (clásico) | Equipos con subwoofer. Sintetiza 20–45 Hz. Espectacular en carro, raro en audífonos. |
| 🎧 **Audífonos** | `EpicenterHeadphonesCore` (nuevo) | Audífonos y bocinas portátiles. Traduce en drivers pequeños. |

**El objetivo de este trabajo es llevar ese motor de Audífonos (y el switch) a Android.**

---

## ⚠️ Hallazgo crítico: el alcance real

**Android hoy NO tiene ningún DSP.** Antes de planear, verifica tú mismo:

- No hay NDK: **no existe** `CMakeLists.txt` ni ningún `.cpp/.h` en `android/`.
- No hay efectos de audio nativos: `MusicScannerPlugin.java` no usa `AudioTrack`, `Equalizer`, `DynamicsProcessing` ni `AudioEffect`.
- No hay `AudioWorklet` ni DSP web en el repo (el script `build:worklet` está anulado: *"iOS-only: native audio route"*).
- `MusicScannerPlugin.java` solo **escanea la biblioteca** y **sirve el audio por un servidor HTTP local** para que el WebView lo reproduzca.

**Conclusión:** esto **no** es "agregar un switch". Hay que **construir la cadena de audio con DSP en Android desde cero**. Estima el trabajo con honestidad y comunícaselo al usuario antes de empezar.

---

## Lo que NO hay que tocar

La **capa web es compartida** entre iOS y Android y **ya está terminada**:

- La UI del switch ya existe en `client/src/components/home/HomeDspView.tsx` (modo Car Audio / Audífonos + textos explicativos).
- Los textos ya están en `client/src/i18n/es.json` y `en.json` (`dsp.modeCar`, `dsp.modeHeadphones`, `dsp.modeCarHint`, `dsp.modeHeadphonesHint`, `dsp.modeHeadphonesKnobs`).
- El hook `client/src/hooks/useIosNativeAudioProcessor.ts` ya guarda el modo y lo manda al nativo.

👉 **No dupliques ni reescribas la UI.** Android solo necesita que el plugin nativo implemente los mismos métodos que ya llama el JS.

---

## API que el plugin Android debe implementar

El JS llama al plugin registrado como **`EpicenterNative`**. Firma mínima para el modo de audífonos:

```ts
setEpicenterMode({ mode: "car" | "headphones" }): Promise<...>
setEpicenterEnabled({ enabled: boolean }): Promise<...>
setEpicenterParams({ intensity, sweepFreq, width, balance, volume }): Promise<...>
```

Consulta `referencia-ios/iosNativeAudio.ts` para la interfaz completa y `referencia-ios/EpicenterNativePlugin.swift` para ver cómo iOS expone cada método.

---

## El DSP (esto es lo bueno: se porta tal cual)

En `dsp/` tienes **C++ puro y portable**, sin dependencias de iOS:

| Archivo | Qué es |
|---|---|
| `EpicenterHeadphonesCore.hpp` | **El motor de audífonos.** Esto es lo que hay que integrar. |
| `EpicenterDSPCore.hpp` / `.cpp` | Dependencias (`BiquadFilter`, `EnvelopeFollower`) **y** el motor clásico de Car Audio. |

Uso:

```cpp
epicenter::HeadphonesBassCore hp;
hp.prepare(sampleRate, channelCount);   // p.ej. 44100.0, 2
hp.setIntensity(100.0f);                // 0..100 (100 = preset validado "fuerte")
float* ch[2] = { left, right };
hp.process(ch, 2, frameCount);          // procesa in-place
hp.reset();                             // al cambiar de pista o de modo
```

### 🚨 Reglas innegociables del DSP

1. **NO modifiques la matemática de `EpicenterHeadphonesCore.hpp`.** Ese sonido se afinó y validó por medición (comparando su huella espectral contra una referencia real) y el usuario lo aprobó explícitamente. Cualquier cambio en filtros, compresión o síntesis rompe el resultado.
2. **Los dos motores nunca corren juntos.** El modo decide cuál procesa; el otro se omite por completo. Mira `referencia-ios/EpicenterDSPBridge.mm` — ahí está exactamente esa lógica de enrutamiento.
3. **Al cambiar de modo hay que llamar `reset()` en ambos motores**, para que no arrastren estado de filtros/fase.
4. En modo Audífonos, **solo la perilla de Intensidad** aplica (mapea a `setIntensity`). Sweep, Width y Balance pertenecen al motor de Car Audio y en la UI ya salen deshabilitadas.

---

## Plan sugerido para Android

1. **Habilitar el NDK**: agrega `externalNativeBuild` con CMake en `android/app/build.gradle` y un `CMakeLists.txt` que compile `EpicenterDSPCore.cpp` + los headers. Usa C++17.
2. **Motor de audio nativo**: reemplaza la reproducción vía servidor HTTP + WebView por una cadena nativa. Recomendado **Oboe** (baja latencia, de Google) o `AudioTrack` en modo streaming. Necesitas:
   - Decodificar el archivo (`MediaCodec`/`MediaExtractor`) a PCM float.
   - En el callback de render, llamar `hp.process(...)` (o el motor clásico según el modo).
3. **Puente JNI**: una clase Java/Kotlin (p.ej. `EpicenterDsp`) con métodos `native` que llamen al C++ (`prepare`, `setIntensity`, `setMode`, `process`, `reset`).
4. **Plugin de Capacitor**: crea `EpicenterNativePlugin.java` con `@CapacitorPlugin(name = "EpicenterNative")` implementando los métodos de la API de arriba, y regístralo en `MainActivity.java`.
5. **Persistencia**: el modo ya se guarda en el lado web (localStorage) y se reenvía al nativo al arrancar; solo asegúrate de aplicarlo cuando llegue.

---

## Criterios de aceptación

- [ ] El switch de la UI cambia el motor en Android (verificable de oído y con un espectrograma).
- [ ] **Modo Audífonos suena idéntico al de iOS.** Compáralo procesando la misma canción en ambas plataformas.
- [ ] Cambiar de modo no produce clics, saltos ni ruidos.
- [ ] La Intensidad al 100% da exactamente el preset "fuerte": `subBoost=18 dB, subGen=2.4, subDepth=0.9, scoop=10 dB`.
- [ ] Con Epicenter apagado el audio pasa **sin alterar** (bit-perfect en lo posible).
- [ ] Sin regresiones en la biblioteca ni en la reproducción existente.

---

## Cómo verificar objetivamente (recomendado)

No confíes solo en el oído. Procesa la **misma canción** en iOS y en Android y compara:

- **Espectro promedio** por bandas (FFT): las curvas deben coincidir dentro de ~1 dB.
- **Crest factor de la banda grave**: debe quedar cerca de 7–8 dB (el sub va muy comprimido).
- **Ancho estéreo**: el grave debe ir prácticamente en mono.

En el repo hay un laboratorio de escritorio (`epicenter-lab/`) con `analyze.py`, que hace justo esta comparación espectral. Reutilízalo.

---

## Advertencia final

Este motor es el diferenciador del producto y el usuario es **muy sensible** a cualquier cambio en su sonido. Si crees que algo debe cambiar, **propónlo y pide aprobación antes de tocarlo**. Corrige bugs (crashes, formatos, latencia) sin alterar la matemática del audio.
