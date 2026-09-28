# Epicenter — Modo Audífonos (paquete para portar a Android)

Todo lo necesario para llevar el motor de graves **"Modo Audífonos"** (ya funcionando en iOS) a la versión de Android.

## Cómo usar este paquete

1. Abre **`PROMPT-ANDROID.md`** y pásalo completo como instrucción inicial al agente/desarrollador que hará el port.
2. Los archivos de `dsp/` se copian tal cual al proyecto Android (es C++ portable).
3. Los de `referencia-ios/` son **solo para consultar** cómo está cableado en iOS; no se copian.

## Contenido

```
dsp/                            ← se porta tal cual (C++ puro, sin dependencias de iOS)
  EpicenterHeadphonesCore.hpp     EL MOTOR NUEVO (modo audífonos)
  EpicenterDSPCore.hpp/.cpp       dependencias (BiquadFilter, EnvelopeFollower) + motor clásico

referencia-ios/                 ← solo para consultar cómo se cablea
  EpicenterDSPBridge.h/.mm        enrutamiento entre los dos motores (la lógica clave)
  EpicenterNativePlugin.swift     cómo iOS expone los métodos al JS
  iosNativeAudio.ts               la interfaz TypeScript que el plugin debe cumplir
  HomeDspView.tsx                 la UI del switch (ya es compartida, no hay que rehacerla)

PROMPT-ANDROID.md               ← el prompt con el plan, reglas y criterios de aceptación
```

## Resumen del motor (para contexto rápido)

El modo Audífonos hace, sobre la señal original:

1. **Sub-seno limpio** sincronizado a la mitad del fundamental del bajo (sin rectificar → no suena sintético).
2. **Solo en bajo profundo**: la síntesis se desvanece entre 90 y 125 Hz de fundamental (arriba de eso causaba oscilaciones raras y además ese fundamental no falta).
3. **Glide de frecuencia** que elimina el micro-warble del oscilador.
4. **Scoop de 80–250 Hz** para quitar el "barro"/boom.
5. **Compresión dura del sub** → densidad y profundidad percibida.
6. **Bajo en mono** → pegada y compatibilidad.

Preset validado y aprobado (**Intensidad = 100**):
`subBoost = 18 dB · subGen = 2.4 · subDepth = 0.9 · monoHz = 105 · scoop = 10 dB`

## ⚠️ Lo más importante

**No cambies la matemática del DSP.** Se afinó midiendo su huella espectral contra una referencia real y el usuario la aprobó. Los detalles y las reglas están en `PROMPT-ANDROID.md`.
