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

## SOCKS5 y reglas por dominio

- SOCKS5 (RFC 1928) en `192.168.49.1:1080`, sin autenticación. Soporta `CONNECT` (IPv4/IPv6/dominio) y `UDP ASSOCIATE`
  (relay UDP con un `DatagramSocket` atado a la red móvil; sin fragmentación; la asociación vive mientras dure el TCP de control).
- Reglas por dominio (pantalla "Reglas y SOCKS5"): sufijo de dominio + PERMITIR/BLOQUEAR; gana la más específica; por defecto permitido.
  Se aplican en SOCKS5 y en el proxy HTTP (8282).
- mihomo/Clash:

```yaml
proxies:
  - name: telefono
    type: socks5
    server: 192.168.49.1
    port: 1080
    udp: true
```
