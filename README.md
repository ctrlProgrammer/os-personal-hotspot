# os-personal-hotspot

App Android personal, sin anuncios, que comparte la conexión móvil del teléfono con otros dispositivos:

1. Crea un grupo Wi-Fi Direct (`WifiP2pManager`), no el hotspot nativo.
2. Levanta un proxy HTTP/CONNECT en `192.168.49.1:8282`.
3. Reenvía el tráfico por la red móvil del teléfono.

Cliente de referencia: túnel `mihomo` en Linux apuntando al proxy.
