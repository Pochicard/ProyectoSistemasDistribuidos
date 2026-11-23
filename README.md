# Sistema de Reserva de Boletos (SRB) — Sistemas Distribuidos

Santiago Martínez y Germán Rodríguez — Pontificia Universidad Javeriana, 2026-30

Arquitectura de tres capas comunicada con ZeroMQ (JeroMQ 0.5.4), Java 17, base de datos SQLite.
Versión final: escalable (varios RMC, Persistencia multihilo) y tolerante a fallas (GT en par
Binary Star, reenvío automático si cae un RMC, operaciones idempotentes).

## 1. Arquitectura final

```
 MÁQUINA 1 (consumidores)        MÁQUINA 2 (negocio, 192.168.56.101)                     MÁQUINA 3 (almacenamiento, 192.168.56.102)

 Cliente / Generador  REQ ──┬──▶ GT primario  ROUTER :5555 ─┐ DEALER ──▶ RMC 1  REP :5556 ─┐ REQ
   (Lazy Pirate:            │      PUB :5003 ⇄ SUB          ├────────▶ RMC 2  REP :5566 ─┼────▶ Persistencia ROUTER :5557
    timeout y cambio de GT) │                 heartbeats     │                            │       proxy ⇄ N hilos (REP)
                            └──▶ GT respaldo  ROUTER :5565 ─┘                            │       SQLite datos/srb.db
                                   PUB :5004 ⇄ SUB                                        │
                                       │ PUSH                                             │
                                       ▼                                                  │
                                 Servicio de Cancelaciones  PULL :5558 ── REQ (LIBERAR) ──┘
                                       │ PUSH  NOTIF:<idReserva>:CANCELADA
 Cliente  PULL :6001  ◀────────────────┘
```

| Componente | Máquina | Puerto | Sockets | Patrón |
|---|---|---|---|---|
| Cliente / generador de carga | 1 | 6001+ (notificaciones) | REQ, PULL | Request/Reply + Lazy Pirate; recibe notificaciones |
| GT primario | 2 | 5555, 5003 | ROUTER, DEALER, PUSH, PUB, SUB | Request/Reply, Pipeline, Binary Star |
| GT respaldo | 2 | 5565, 5004 | ROUTER, DEALER, PUSH, PUB, SUB | Igual; pasivo hasta que falla el primario |
| RMC 1 y RMC 2 | 2 | 5556, 5566 | REP, REQ | Request/Reply |
| Servicio de Cancelaciones | 2 | 5558 | PULL, REQ, PUSH | Pipeline (asíncrono) |
| Persistencia | 3 | 5557 | ROUTER, DEALER inproc, REP | Servidor multihilo (proxy) |

### Escalabilidad
- **Varios RMC**: el GT usa un DEALER por RMC (asíncrono) y envía cada solicitud al RMC con menos
  trabajo en curso. Agregar un RMC = arrancar otro proceso y añadirlo en `--rmc`.
- **GT asíncrono**: con ROUTER atiende muchas solicitudes a la vez, en lugar de una por una.
- **Persistencia multihilo**: un ROUTER reparte las solicitudes entre `--hilos` trabajadores, cada uno con
  su conexión a SQLite (modo WAL: las consultas corren en paralelo; las escrituras se ordenan).

### Tolerancia a fallas
- **Falla del GT — Binary Star**: dos GT intercambian heartbeats cada 1 s. Si el activo deja de
  enviarlos por 2 s y llega una solicitud, el pasivo toma el control (failover). Si el caído vuelve,
  queda como pasivo. El cliente, si no recibe respuesta, reintenta la misma solicitud en el otro GT.
- **Falla de un RMC**: si un RMC no responde en 2 s, el GT reenvía sus solicitudes pendientes a otro
  RMC; el cliente no se entera. Cuando el RMC vuelve, se usa de nuevo.
- **Continuar donde quedó, sin duplicar**: cada operación lleva un `idSol` único. La Persistencia lo
  guarda en la tabla `solicitud` en la misma transacción que el cambio; un reintento recibe la
  respuesta guardada y no se aplica dos veces.

## 2. Compilar

Requisitos: JDK 17+ y Maven 3.8+.

```bash
mvn clean package
```

Genera **`target/srb.jar`** con JeroMQ, Gson y el driver de SQLite incluidos. Copie ese archivo a las máquinas 2 y 3:

```powershell
scp target\srb.jar srb@192.168.56.101:~
scp target\srb.jar srb@192.168.56.102:~
```

## 3. Ejecutar en tres máquinas (en este orden)

```bash
# MÁQUINA 3 (srb-datos) — Persistencia. --reiniciar vuelve a los 50 eventos con 100 asientos.
java -cp srb.jar persistencia.PersistenciaMain --puerto=5557 --hilos=4 --reiniciar

# MÁQUINA 2 (srb-negocio) — una terminal por proceso
java -cp srb.jar rmc.RmcMain --puerto=5556 --persistencia=192.168.56.102:5557
java -cp srb.jar rmc.RmcMain --puerto=5566 --persistencia=192.168.56.102:5557
java -cp srb.jar cancelaciones.CancelacionesMain --puerto=5558 --persistencia=192.168.56.102:5557
java -cp srb.jar gt.GtMain --rol=primario --puerto=5555 --rmc=localhost:5556,localhost:5566
java -cp srb.jar gt.GtMain --rol=respaldo --puerto=5565 --rmc=localhost:5556,localhost:5566

# MÁQUINA 1 (Windows) — cliente o generador de carga
java -cp target\srb.jar cliente.ClienteMain --gt=192.168.56.101:5555,192.168.56.101:5565 --ip=192.168.56.1 --puerto-notif=6001
java -cp target\srb.jar pruebas.GeneradorCargaMain --escenario=A --gt=192.168.56.101:5555,192.168.56.101:5565 --ip=192.168.56.1
```

En el cliente y el generador, `--gt` lleva el GT primario y luego el de respaldo (si el primario no
responde, el cliente reintenta en el otro) e `--ip` es la IP de Windows en la red de VirtualBox: es la
dirección a la que el Servicio de Cancelaciones envía las notificaciones. Sin `--ip=192.168.56.1` las
notificaciones salen hacia `127.0.0.1` de la VM y no llegan.

Opciones útiles del GT: `--metricas=60` (imprime el TPM por tipo cada 60 s), `--timeout-rmc=2000`.

**En las pruebas de carga, arranque GT, RMC y Persistencia con `--silencioso`.** Imprimir una línea
por solicitud en una ventana SSH frena a los propios procesos (con miles de solicitudes, la consola se
vuelve el cuello de botella) y distorsiona las medidas. Para la demostración en la sustentación, sin
`--silencioso` se ve cada operación.

**Arquitectura base (para comparar en el informe):** un solo GT sin `--rol` y un solo RMC:

```bash
java -cp srb.jar persistencia.PersistenciaMain --puerto=5557 --hilos=1 --reiniciar
java -cp srb.jar rmc.RmcMain --puerto=5556 --persistencia=192.168.56.102:5557
java -cp srb.jar gt.GtMain --puerto=5555 --rmc=localhost:5556
```

**Probar la tolerancia a fallas:** con el generador corriendo, Ctrl + C en el GT primario (el respaldo
pasa a ACTIVE) o en un RMC (el GT reenvía al otro). En la Persistencia aparecen como
`reintento: se devuelve la respuesta guardada` las solicitudes repetidas que no se aplicaron dos veces.

**Firewall de Windows:** las notificaciones de cancelación llegan del Servicio de Cancelaciones (VM) al
cliente (Windows). La primera vez que el cliente abre su puerto, Windows pregunta si permite a Java:
marque redes **privadas y públicas**. O, en PowerShell como administrador:
`netsh advfirewall firewall add rule name="SRB notificaciones" dir=in action=allow protocol=TCP localport=6001-6020`

### Todo en una máquina (desarrollo)

Los valores por defecto usan `localhost`, así que basta con arrancar los procesos sin direcciones
(`--persistencia`, `--rmc` y `--cancelaciones` por defecto apuntan a localhost).

## 4. Protocolo de mensajes (texto UTF-8, una trama)

Definido en `comun/Protocolo.java`. `idSol` = UUID que genera el cliente y repite en los reintentos.

| Operación | El cliente envía | Respuesta |
|---|---|---|
| Consulta | `CONSULTA` o `CONSULTA:<idSol>` | JSON con los eventos |
| Reserva | `RESERVA:<idSol>:<evento>:<ocurrencia>:<cantidad>` | `OK:<idReserva>` o `RECHAZADO` |
| Reserva (formato 1.ª entrega) | `RESERVA:<evento>:<ocurrencia>:<cantidad>` | `OK` o `RECHAZADO` |
| Modificación | `MODIFICAR:<idSol>:<idReserva>:<ocurrenciaNueva>:<cantidadNueva>` | `OK` o `RECHAZADO` |
| Cancelación | `CANCELAR:<idSol>:<idReserva>:<ipCliente>:<puertoNotif>` | `ACEPTADA` de inmediato |
| Notificación (después, al PULL del cliente) | — | `NOTIF:<idReserva>:CANCELADA` o `RECHAZADA` |
| Interno: Cancelaciones → Persistencia | `LIBERAR:<idSol>:<idReserva>` | `OK` o `RECHAZADO` |

Errores: `ERROR:<explicación>`.

## 5. Base de datos SQLite (máquina 3, `datos/srb.db`)

| Tabla | Contenido |
|---|---|
| `evento` | id, ocurrencias |
| `ocurrencia` | evento, número, fecha, capacidad, disponibles (`CHECK disponibles BETWEEN 0 AND capacidad`) |
| `reserva` | id, evento, ocurrencia, cantidad, estado ACTIVA/CANCELADA |
| `solicitud` | idSol, operación, respuesta (idempotencia) |
| `transaccion` | bitácora de todas las operaciones |

Cada escritura es una transacción `BEGIN IMMEDIATE` con `synchronous=FULL`: se confirma en disco antes
de responder. El descuento de asientos es un `UPDATE ... WHERE disponibles >= ?`, así que nunca se sobrevende.

```bash
sudo apt install -y sqlite3
sqlite3 -header -column ~/datos/srb.db "SELECT * FROM ocurrencia WHERE disponibles < capacidad;"
sqlite3 -header -column ~/datos/srb.db "SELECT * FROM reserva ORDER BY id DESC LIMIT 10;"
sqlite3 -header -column ~/datos/srb.db "SELECT * FROM transaccion ORDER BY id DESC LIMIT 10;"
```

## 6. Pruebas de carga

`pruebas.GeneradorCargaMain` simula los escenarios de la Tabla 1 (A = 3 clientes, B = 5, C = 8). Use
`--ayuda` para ver los parámetros (`--rpm`, `--pct-reserva`, `--duracion`, ...). Los resultados (CSV y
resumen con promedio, desviación estándar y TPM) quedan en `resultados/`. Reinicie la Persistencia
con `--reiniciar` entre pruebas independientes.
