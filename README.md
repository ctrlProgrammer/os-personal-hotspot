# OS Hotspot

App Android personal, **sin anuncios**, que comparte la conexión móvil del teléfono con otros dispositivos
mediante **Wi-Fi Direct** y un proxy **HTTP/CONNECT + SOCKS5 (con UDP)**, e incluye un panel de tráfico y
herramientas de red.

> Proyecto personal y experimental. Probado en un Samsung Galaxy A17 con Android 16.

## Cómo funciona

```
 PC / otro dispositivo                    Teléfono (OS Hotspot)                  Internet
┌───────────────────────┐  Wi-Fi Direct  ┌────────────────────────────┐  red móvil
│ cliente proxy / túnel │ ─────────────▶ │ HTTP/CONNECT  192.168.49.1:8282 │ ─────────▶
│ (mihomo, curl, ...)   │                │ SOCKS5 + UDP  192.168.49.1:1080 │
└───────────────────────┘                └────────────────────────────┘
```

1. Crea un grupo **Wi-Fi Direct** (`WifiP2pManager`) con un nombre y una clave WPA2 propios, en 5 GHz si el
   teléfono lo permite (si no, banda automática, y como último recurso el grupo por defecto del sistema).
2. Levanta dos proxies en el teléfono (IP fija del grupo: `192.168.49.1`).
3. Las conexiones salientes se **atan a la red móvil** (`TRANSPORT_CELLULAR`), aunque el teléfono esté
   conectado a un Wi-Fi.
4. Corre como servicio en primer plano, con notificación y botón *Apagar*.

## Funciones

| Pantalla | Qué hace |
|---|---|
| **Principal** | Encender/apagar, estado, nombre de la red, clave y direcciones de los proxies (con botón *Copiar*). |
| **Tráfico** | Velocidad de subida/bajada en vivo, gráfico de 60 s, totales de la sesión y consumo de los últimos 7 días. |
| **Dispositivos** | Dispositivos del grupo con su consumo; expulsar o bloquear por IP. |
| **Conexiones** | Registro de conexiones (host, puerto, dispositivo, duración, bytes) con búsqueda, filtros, *Top* de dominios y exportación a CSV. |
| **Herramientas** | Ping TCP, DNS, traceroute, speedtest e IP pública, ejecutados por la red móvil del teléfono. |
| **Reglas y SOCKS5** | Reglas por dominio (permitir/bloquear) que se aplican a ambos proxies. |

### Proxies

- **HTTP/CONNECT** en `192.168.49.1:8282`: HTTP plano y túneles `CONNECT` (HTTPS y cualquier TCP).
- **SOCKS5** (RFC 1928) en `192.168.49.1:1080`, sin autenticación: `CONNECT` (IPv4, IPv6 y dominio) y
  `UDP ASSOCIATE` (relay UDP atado a la red móvil, sin fragmentación; la asociación vive mientras dure el TCP
  de control). `BIND` no está soportado.
- **Reglas por dominio:** sufijo de dominio + `PERMITIR`/`BLOQUEAR`; gana la regla más específica; por defecto
  todo está permitido.

## Compilar e instalar

Requisitos: JDK 17+, Android SDK (platform 35, build-tools 35.0.0). `minSdk 29`.

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Uso

1. Abre la app y pulsa **Encender** (acepta los permisos de *Dispositivos Wi-Fi cercanos* y notificaciones).
2. Conecta el otro dispositivo a la red `DIRECT-OS-Hotspot` con la clave que muestra la app.
3. Configura el proxy en el cliente: `192.168.49.1:8282` (HTTP) o `192.168.49.1:1080` (SOCKS5).

Prueba rápida desde el cliente:

```bash
curl --proxy http://192.168.49.1:8282 https://api.ipify.org
curl --socks5-hostname 192.168.49.1:1080 https://api.ipify.org
```

### Túnel transparente en Linux (todas las apps, incluido UDP)

Con [mihomo](https://github.com/MetaCubeX/mihomo) en modo TUN, apuntando al SOCKS5 del teléfono:

```yaml
tun:
  enable: true
  stack: gvisor
  auto-route: true
  auto-detect-interface: true
  dns-hijack: [any:53]
  route-exclude-address: [192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, 127.0.0.0/8]
dns:
  enable: true
  enhanced-mode: fake-ip
  nameserver: ["https://1.1.1.1/dns-query#telefono"]
proxies:
  - name: telefono
    type: socks5
    server: 192.168.49.1
    port: 1080
    udp: true
rules:
  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
  - MATCH,telefono
```

Excluye la red del teléfono (`192.168.0.0/16`) para no crear un bucle. Hace falta `cap_net_admin` para el binario
o ejecutarlo como root.

## Limitaciones y seguridad

- **Los proxies no tienen autenticación.** La protección es la clave WPA2 del grupo Wi-Fi Direct: quien esté
  conectado a esa red puede usar tu conexión. Usa una clave larga y bloquea los dispositivos que no reconozcas.
- El grupo Wi-Fi Direct es **único por teléfono**: no puede convivir con otra app que cree el suyo.
- Las estadísticas y el registro de conexiones viven **en memoria** (se pierden al apagar la app); solo el consumo
  diario se guarda.
- En **Dispositivos**, Android restringe la lectura de la tabla ARP, por lo que a veces la IP aparece como
  «desconocida» hasta que el dispositivo genera tráfico.
- No hay pruebas automáticas; la app se ha probado a mano en un solo teléfono.
- Revisa las condiciones de tu plan móvil: algunos operadores limitan o prohíben compartir la conexión.

## Estructura

```
app/src/main/kotlin/dev/ctrl/hotspot/
├── HotspotService.kt   servicio en primer plano: grupo Wi-Fi Direct + proxies
├── ProxyServer.kt      proxy HTTP/CONNECT
├── Socks5Server.kt     SOCKS5 (CONNECT + UDP ASSOCIATE)
├── StatsStore.kt       estadísticas compartidas (conexiones, bytes)
├── Rules.kt            reglas por dominio        Blocklist.kt  bloqueo por IP
├── Ui.kt               kit visual (tema oscuro, tarjetas, márgenes seguros)
└── *Activity.kt        pantallas
```

## Licencia

Sin licencia por ahora: todos los derechos reservados.
