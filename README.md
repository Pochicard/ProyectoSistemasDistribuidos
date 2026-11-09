# Sistema de Reserva de Boletos (SRB) — Sistemas Distribuidos

Santiago Martínez y Germán Rodríguez — Pontificia Universidad Javeriana, 2026-30

Arquitectura de tres capas comunicada con ZeroMQ (JeroMQ 0.5.4), Java 17, base de datos SQLite.

```
 MÁQUINA 1 (consumidores)      MÁQUINA 2 (negocio)                 MÁQUINA 3 (almacenamiento)
 ┌───────────────────────┐     ┌──────────────┐   ┌─────────┐      ┌──────────────────────────┐
 │ ClienteMain           │REQ  │ GT           │REQ│ RMC     │ REQ  │ Persistencia             │
 │ GeneradorCargaMain    │────▶│ REP :5555    │──▶│REP :5556│─────▶│ REP :5557                │
 │                       │◀────│              │◀──│         │◀─────│ datos/srb.db (SQLite)    │
 └───────────────────────┘     └──────────────┘   └─────────┘      │                          │
                                                                    └──────────────────────────┘
```

## 1. Compilar (una sola vez)

Requisitos: JDK 17+ y Maven 3.8+.

```bash
mvn clean package
```

Se genera **`target/srb.jar`** con JeroMQ, Gson y el driver de SQLite incluidos. Copie ese único archivo a las tres máquinas.

## 2. Ejecutar en TRES máquinas

Ejemplo con estas IP (cámbielas por las reales; en Linux `hostname -I`, en Windows `ipconfig`):

| Máquina | Capa | IP de ejemplo | Procesos | Puertos que debe abrir |
|---|---|---|---|---|
| 1 | Consumidores | 10.43.100.11 | Clientes / generador de carga | — |
| 2 | Negocio | 10.43.100.12 | GT y RMC | 5555 (y 5556 si el RMC va en otra máquina) |
| 3 | Almacenamiento | 10.43.100.13 | Persistencia | 5557 |

Arranque en este orden (de abajo hacia arriba):

```bash
# MÁQUINA 3 — Persistencia (imprime el estado inicial de la BD)
java -cp srb.jar persistencia.PersistenciaMain --puerto=5557 --datos=./datos --reiniciar

# MÁQUINA 2 — RMC y GT (dos terminales)
java -cp srb.jar rmc.RmcMain --puerto=5556 --persistencia=10.43.100.13:5557
java -cp srb.jar gt.GtMain   --puerto=5555 --rmc=localhost:5556

# MÁQUINA 1 — Cliente interactivo o generador de carga
java -cp srb.jar cliente.ClienteMain --gt=10.43.100.12:5555
java -cp srb.jar pruebas.GeneradorCargaMain --escenario=A --gt=10.43.100.12:5555
```

Ninguna IP está escrita en el código: cada servicio recibe la dirección de la capa siguiente por argumento
(o por variable de entorno: `SRB_RMC`, `SRB_PERSISTENCIA`, `SRB_PUERTO`, `SRB_DATOS`).

**Firewall.** Si un servicio no conecta, abra el puerto en la máquina que hace `bind`:
- Linux (ufw): `sudo ufw allow 5557/tcp`
- Windows: `netsh advfirewall firewall add rule name="SRB" dir=in action=allow protocol=TCP localport=5555-5557`

Comprobación rápida desde otra máquina: `nc -zv 10.43.100.13 5557` (Linux) o `Test-NetConnection 10.43.100.13 -Port 5557` (PowerShell).

### Despliegue usado: 1 equipo anfitrión + 2 máquinas virtuales (VirtualBox)

| Máquina | Dónde | Red | IP | Procesos |
|---|---|---|---|---|
| 1 Consumidores | Equipo anfitrión | Host-only (vboxnet0) | 192.168.56.1 | Clientes / generador de carga |
| 2 Negocio | VM `srb-negocio` (Ubuntu Server) | NAT + Host-only | 192.168.56.101 | GT y RMC |
| 3 Almacenamiento | VM `srb-datos` (Ubuntu Server) | NAT + Host-only | 192.168.56.102 | Persistencia |

Cada VM tiene dos adaptadores: **NAT** (salida a internet para instalar Java) y **Red solo-anfitrión**
(comunicación entre el anfitrión y las VM, con IP fijas). La red solo-anfitrión no depende del Wi-Fi, así que
funciona igual en la universidad.

```bash
# VM srb-datos (192.168.56.102)
java -cp srb.jar persistencia.PersistenciaMain --puerto=5557 --reiniciar
# VM srb-negocio (192.168.56.101), dos terminales
java -cp srb.jar rmc.RmcMain --puerto=5556 --persistencia=192.168.56.102:5557
java -cp srb.jar gt.GtMain   --puerto=5555 --rmc=localhost:5556
# Anfitrión
java -cp target/srb.jar cliente.ClienteMain --gt=192.168.56.101:5555
```

### Todo en una máquina (para desarrollo)

Los valores por defecto apuntan a `localhost`, así que basta con:

```bash
java -cp target/srb.jar persistencia.PersistenciaMain
java -cp target/srb.jar rmc.RmcMain
java -cp target/srb.jar gt.GtMain
java -cp target/srb.jar cliente.ClienteMain
```

## 3. Persistencia: base de datos SQLite

El Servicio de Persistencia guarda los datos en una **base de datos SQLite**, el archivo `datos/srb.db`
en la máquina 3. SQLite es un motor relacional embebido: el driver (`sqlite-jdbc`) viaja dentro de `srb.jar`,
así que no hay que instalar un servidor de BD aparte.

Esquema:

| Tabla | Columnas |
|---|---|
| `evento` | `id` (PK), `ocurrencias` |
| `ocurrencia` | `evento_id` (FK), `numero`, `fecha`, `capacidad`, `disponibles` — `CHECK (disponibles BETWEEN 0 AND capacidad)` |
| `transaccion` | `id`, `fecha_hora`, `operacion`, `detalle`, `resultado` (bitácora de operaciones) |

- **Primera ejecución o `--reiniciar`**: carga inicial del enunciado: 50 eventos (E1–E20 con dos ocurrencias,
  E21–E50 con una), 100 asientos por ocurrencia.
- **Ejecuciones siguientes**: continúa con los datos guardados. Si se reinicia la máquina 3, las reservas
  confirmadas siguen ahí.
- **Caso de prueba del profesor**: `--carga=caso.json` (mismo formato JSON que devuelve `CONSULTA`).
- **Reserva atómica**: `UPDATE ocurrencia SET disponibles = disponibles - ? WHERE ... AND disponibles >= ?`
  dentro de una transacción junto con el registro en `transaccion`. Si actualiza 1 fila, `OK`; si 0, `RECHAZADO`.
  Nunca se sobrevende. Con `PRAGMA synchronous=FULL`, cada `COMMIT` queda escrito en disco antes de responder.

### Ver la base de datos (en la máquina 3)

```bash
sudo apt install -y sqlite3          # una sola vez
sqlite3 -header -column ~/datos/srb.db "SELECT * FROM ocurrencia WHERE evento_id='E1';"
sqlite3 -header -column ~/datos/srb.db "SELECT evento_id, numero, capacidad-disponibles AS ocupados FROM ocurrencia WHERE disponibles < capacidad;"
sqlite3 -header -column ~/datos/srb.db "SELECT * FROM transaccion ORDER BY id DESC LIMIT 10;"
```

## 4. Protocolo de mensajes (texto UTF-8, un frame)

| Solicitud | Respuesta |
|---|---|
| `CONSULTA` | JSON con los eventos: `{"E1":{"id":"E1","ocurrencias":2,...},...}` |
| `RESERVA:<evento>:<ocurrencia>:<cantidad>` (p. ej. `RESERVA:E3:1:2`) | `OK` o `RECHAZADO` |
| otro | `ERROR:...` |

## 5. Patrones de comunicación

- **Consulta y reserva → patrón síncrono Request/Reply (REQ/REP) de ZeroMQ.** El cliente envía con un socket
  REQ y queda bloqueado hasta la respuesta. El GT (REP hacia clientes, REQ hacia el RMC) reenvía y espera; el
  RMC (REP hacia el GT, REQ hacia la Persistencia) hace lo mismo. La respuesta solo sube cuando la Persistencia
  ya guardó el cambio.
- **Cancelación (segunda entrega) → patrón asíncrono** (PUSH/PULL o PUB/SUB): el GT acepta la cancelación sin
  esperar a la Persistencia y el servicio de cancelaciones notifica al cliente después.

## 6. Pruebas de carga

`pruebas.GeneradorCargaMain` simula los escenarios de la Tabla 1 (A = 3 clientes, B = 5, C = 8). Use
`--ayuda` para ver los parámetros (`--rpm`, `--pct-reserva`, `--duracion`, ...). Los resultados (CSV y resumen
con promedio, desviación estándar y TPM) quedan en `resultados/`.
