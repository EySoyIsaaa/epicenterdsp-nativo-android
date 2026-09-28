import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.epicenter.hifi',
  appName: 'EpicenterDSP Player',
  webDir: 'dist/public',
  server: {
    androidScheme: 'https',
  },
  android: {
    backgroundColor: '#000000',
    allowMixedContent: true,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    SplashScreen: {
      // Corto a propósito: el splash nativo solo cubre el arranque del WebView y
      // luego entrega a la intro animada de la app. Ambos son negros, así que el
      // relevo no se nota. Con 1500 ms el usuario esperaba splash + intro.
      launchShowDuration: 600,
      backgroundColor: '#000000',
      showSpinner: false,
      launchAutoHide: true,
    },
  },
};

export default config;
