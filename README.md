# os-personal-hotspot

App Android personal, sin anuncios, que comparte la conexión móvil del teléfono con otros dispositivos:

1. Crea un grupo Wi-Fi Direct (`WifiP2pManager`), no el hotspot nativo.
2. Levanta un proxy HTTP/CONNECT en `192.168.49.1:8282`.
3. Reenvía el tráfico por la red móvil del teléfono.

Cliente de referencia: túnel `mihomo` en Linux apuntando al proxy.

## Compilar

```
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 17+, Android SDK (platform 35, build-tools 35.0.0). minSdk 29.

## Uso

Abrir la app → Encender. Muestra la red (`DIRECT-OS-Hotspot`), la clave y el proxy `192.168.49.1:8282`.
El tráfico sale por la red móvil (se pide `TRANSPORT_CELLULAR` explícito).
