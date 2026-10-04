# Epicenter Hi-Fi

Epicenter Hi-Fi es un reproductor de música local. La aplicación Android usa una interfaz nativa de Jetpack Compose y procesa el audio dentro de Media3, Java/Kotlin y C++ (NDK/JNI). El APK no incluye una WebView ni necesita generar o sincronizar una aplicación web.

## Aplicación Android

- Biblioteca local indexada desde MediaStore y archivos importados con el selector del sistema.
- Reproducción en segundo plano con Media3, sesión multimedia y controles de notificación.
- Cola editable, playlists persistentes y búsqueda local.
- Epicenter DSP con perfiles Car Audio y Audífonos.
- Ecualizador gráfico de 31 bandas, espectro en vivo y ajustes automáticos.
- Reverb, sala de conciertos y fundido de pista configurable.
- Interfaz nativa con transiciones, tema claro/oscuro e idioma español/inglés.

### Flujo de audio Android

`Media3 / AudioTrack -> Epicenter C++ -> EQ 31 bandas -> efectos -> salida`

### Compilar

Desde la raíz del repositorio, `./build.sh` compila el APK de depuración. También se puede abrir `android/` en Android Studio. El proyecto requiere JDK 17, Android SDK y NDK/CMake.

## Cliente web independiente

`client/`, `server/` y `shared/` conservan el cliente web React/TypeScript y su servidor. Se compilan con `pnpm build`; no forman parte del APK Android.

## Estructura

- `android/`: aplicación Android nativa, Compose, Media3, Room y DSP C++.
- `client/`: cliente web React/TypeScript y lógica de audio Web Audio.
- `server/`: servidor Express/tRPC.
- `shared/`: tipos y utilidades compartidos.
