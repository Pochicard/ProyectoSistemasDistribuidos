package gt;

import comun.Argumentos;
import comun.Protocolo;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Gestor de Transacciones (GT): puerta de entrada del sistema. MAQUINA 2 (srb-negocio).
 *
 * <h2>Canales y sockets</h2>
 * <pre>
 *  Clientes (REQ) ──▶ ROUTER :5555 ──┬─ canal sincrono ──▶ DEALER ──▶ RMC 1 (REP :5556)
 *                                    │   (BalanceadorRmc)  DEALER ──▶ RMC 2 (REP :5566)
 *                                    └─ canal asincrono ─▶ PUSH ──▶ Servicio de Cancelaciones (PULL :5558)
 *  GT primario  PUB :5003 ──heartbeat──▶ SUB  GT respaldo
 *  GT respaldo  PUB :5004 ──heartbeat──▶ SUB  GT primario
 * </pre>
 *
 * <ul>
 *   <li><b>Sincrono</b> (CONSULTA, RESERVA, MODIFICAR): el cliente espera la respuesta (Request/Reply).
 *       El GT usa ROUTER para atender a varios clientes a la vez y reparte entre los RMC.</li>
 *   <li><b>Asincrono</b> (CANCELAR): el GT responde {@code ACEPTADA} de inmediato y envia la
 *       cancelacion por PUSH (patron Pipeline). El Servicio de Cancelaciones la procesa despues y
 *       notifica al cliente.</li>
 *   <li><b>Tolerancia a fallas</b>: con {@code --rol} se arrancan dos GT en par Binary Star
 *       (ver {@link BinaryStar}). Sin {@code --rol}, el GT trabaja solo y siempre esta activo.
 *       La caida de un RMC la maneja {@link BalanceadorRmc}.</li>
 *   <li><b>Metricas</b>: cada {@code --metricas} segundos imprime las transacciones procesadas
 *       por tipo en ese intervalo (TPM medido en el GT).</li>
 * </ul>
 *
 * <pre>
 * Uso:
 *   GT unico:     java -cp srb.jar gt.GtMain --puerto=5555 --rmc=localhost:5556,localhost:5566
 *   GT primario:  java -cp srb.jar gt.GtMain --rol=primario --puerto=5555 --rmc=localhost:5556,localhost:5566
 *   GT respaldo:  java -cp srb.jar gt.GtMain --rol=respaldo --puerto=5565 --rmc=localhost:5556,localhost:5566
 * Opciones:
 *   --cancelaciones=host:5558   Servicio de Cancelaciones (defecto localhost:5558)
 *   --estado-pub=5003           puerto donde este GT publica su estado (primario 5003, respaldo 5004)
 *   --estado-par=host:5004      PUB del otro GT (primario: localhost:5004, respaldo: localhost:5003)
 *   --timeout-rmc=2000          ms sin respuesta para dar por caido un RMC
 *   --metricas=60               cada cuantos segundos imprimir el TPM (0 = nunca)
 *   --silencioso                no imprimir una linea por solicitud (para pruebas de carga)
 * </pre>
 */
public class GtMain {

    private enum Tipo { CONSULTA, RESERVA, MODIFICAR, CANCELAR, ERROR }

    public static void main(String[] args) {
        Argumentos a = new Argumentos(args);
        int puerto = a.entero("puerto", 5555);
        List<String> rmcs = listaEndpoints(a.texto("rmc", "localhost:5556"));
        String cancelaciones = a.endpoint("cancelaciones", "localhost:5558");
        String rol = a.texto("rol", "");
        if (!rol.isEmpty() && !rol.equals("primario") && !rol.equals("respaldo")) {
            throw new IllegalArgumentException("--rol debe ser primario o respaldo");
        }
        boolean parBinaryStar = !rol.isEmpty();
        boolean primario = rol.equals("primario");
        int pubEstado = a.entero("estado-pub", primario ? 5003 : 5004);
        String parEstado = a.endpoint("estado-par", primario ? "localhost:5004" : "localhost:5003");
        int timeoutRmc = a.entero("timeout-rmc", 2000);
        int intervaloMetricas = a.entero("metricas", 60);
        boolean silencioso = a.tiene("silencioso");
        String nombre = parBinaryStar ? "GT " + rol : "GT";

        try (ZContext context = new ZContext()) {
            ZMQ.Socket frontend = context.createSocket(SocketType.ROUTER);
            frontend.bind("tcp://*:" + puerto);

            ZMQ.Socket cancel = context.createSocket(SocketType.PUSH);
            cancel.setLinger(1000);
            cancel.connect(cancelaciones);

            BalanceadorRmc balanceador = new BalanceadorRmc(context, rmcs, timeoutRmc, 3000);

            long ahora = System.currentTimeMillis();
            BinaryStar bstar = null;
            ZMQ.Socket estadoPub = null;
            ZMQ.Socket estadoSub = null;
            if (parBinaryStar) {
                bstar = new BinaryStar(primario, ahora);
                estadoPub = context.createSocket(SocketType.PUB);
                estadoPub.bind("tcp://*:" + pubEstado);
                estadoSub = context.createSocket(SocketType.SUB);
                estadoSub.subscribe(ZMQ.SUBSCRIPTION_ALL);
                estadoSub.connect(parEstado);
            }

            System.out.println("==========================================");
            System.out.println("[" + nombre + "] Clientes en tcp://*:" + puerto + " (ROUTER)");
            System.out.println("[" + nombre + "] RMC: " + String.join(", ", rmcs) + " (DEALER, timeout " + timeoutRmc + " ms)");
            System.out.println("[" + nombre + "] Cancelaciones: " + cancelaciones + " (PUSH)");
            if (parBinaryStar) {
                System.out.println("[" + nombre + "] Binary Star: publica estado en :" + pubEstado
                        + ", escucha al par en " + parEstado + ", estado inicial " + bstar.estado());
            }
            System.out.println("==========================================");

            Map<Tipo, Integer> intervalo = new EnumMap<>(Tipo.class);
            Map<Tipo, Integer> total = new EnumMap<>(Tipo.class);
            long proximoHeartbeat = ahora;
            long proximasMetricas = ahora + intervaloMetricas * 1000L;
            long ignoradas = 0;

            while (!Thread.currentThread().isInterrupted()) {
                List<ZMQ.Socket> socketsRmc = balanceador.sockets();
                try (ZMQ.Poller poller = context.createPoller(2 + socketsRmc.size())) {
                    poller.register(frontend, ZMQ.Poller.POLLIN);
                    if (estadoSub != null) {
                        poller.register(estadoSub, ZMQ.Poller.POLLIN);
                    }
                    for (ZMQ.Socket s : socketsRmc) {
                        poller.register(s, ZMQ.Poller.POLLIN);
                    }
                    poller.poll(100);
                }
                ahora = System.currentTimeMillis();

                // 1. Heartbeats del par (Binary Star)
                if (estadoSub != null) {
                    while ((estadoSub.getEvents() & ZMQ.Poller.POLLIN) != 0) {
                        String e = estadoSub.recvStr(ZMQ.DONTWAIT);
                        if (e == null) {
                            break;
                        }
                        bstar.heartbeatDelPar(BinaryStar.Estado.deCodigo(Integer.parseInt(e)), ahora);
                    }
                }

                // 2. Solicitudes de clientes: [identidad][vacio][mensaje]
                while ((frontend.getEvents() & ZMQ.Poller.POLLIN) != 0) {
                    byte[] cliente = frontend.recv(ZMQ.DONTWAIT);
                    if (cliente == null) {
                        break;
                    }
                    frontend.recv(0);                      // trama vacia que agrega el REQ
                    String mensaje = frontend.recvStr(0);
                    while (frontend.hasReceiveMore()) {
                        frontend.recv(0);
                    }
                    if (bstar != null && !bstar.atenderCliente(ahora)) {
                        if (ignoradas++ % 20 == 0) {
                            System.out.println("[" + nombre + "] Estado " + bstar.estado()
                                    + ": se ignora la solicitud (la atiende el GT activo)");
                        }
                        continue;                          // sin respuesta: el cliente reintenta en el otro GT
                    }
                    String op = Protocolo.operacion(mensaje);
                    if (op.equals(Protocolo.CANCELAR)) {
                        String respuesta;
                        if (mensaje.split(Protocolo.SEP, -1).length != 5) {
                            respuesta = Protocolo.error("Formato esperado CANCELAR:<idSol>:<idReserva>:<ipCliente>:<puertoNotif>");
                            contar(intervalo, total, Tipo.ERROR);
                        } else if (cancel.send(mensaje, ZMQ.DONTWAIT)) {
                            respuesta = Protocolo.ACEPTADA;    // no espera a la Persistencia
                            contar(intervalo, total, Tipo.CANCELAR);
                        } else {
                            respuesta = Protocolo.error("El servicio de cancelaciones no esta disponible");
                            contar(intervalo, total, Tipo.ERROR);
                        }
                        responder(frontend, cliente, respuesta);
                        if (!silencioso) {
                            System.out.println("[" + nombre + "] " + mensaje + " -> " + respuesta + " (canal asincrono)");
                        }
                    } else if (op.equals(Protocolo.CONSULTA) || op.equals(Protocolo.RESERVA) || op.equals(Protocolo.MODIFICAR)) {
                        balanceador.enviar(new BalanceadorRmc.Pendiente(cliente, mensaje, ahora), ahora);
                    } else {
                        responder(frontend, cliente, Protocolo.error("Operacion desconocida: " + op));
                        contar(intervalo, total, Tipo.ERROR);
                    }
                }

                // 3. Respuestas de los RMC
                for (int i = 0; i < balanceador.cantidad(); i++) {
                    while (balanceador.tieneMensaje(i)) {
                        BalanceadorRmc.Respuesta r = balanceador.recibir(i);
                        if (r == null) {
                            continue;
                        }
                        responder(frontend, r.cliente(), r.respuesta());
                        Tipo t = r.respuesta().startsWith(Protocolo.ERROR) ? Tipo.ERROR
                                : Tipo.valueOf(Protocolo.operacion(r.mensaje()));
                        contar(intervalo, total, t);
                        if (!silencioso) {
                            System.out.println("[" + nombre + "] " + r.mensaje() + " -> "
                                    + (r.respuesta().startsWith("{") ? "JSON de eventos" : r.respuesta()));
                        }
                    }
                }

                // 4. RMC caidos, solicitudes en espera y vencidas
                for (BalanceadorRmc.Pendiente p : balanceador.revisar(ahora)) {
                    responder(frontend, p.cliente(), Protocolo.error("Ningun RMC disponible"));
                    contar(intervalo, total, Tipo.ERROR);
                }

                // 5. Heartbeat propio
                if (estadoPub != null && ahora >= proximoHeartbeat) {
                    estadoPub.send(String.valueOf(bstar.estado().codigo()));
                    proximoHeartbeat = ahora + BinaryStar.HEARTBEAT_MS;
                }

                // 6. Metricas (TPM en el GT)
                if (intervaloMetricas > 0 && ahora >= proximasMetricas) {
                    System.out.println("[" + nombre + "] Ultimos " + intervaloMetricas + " s: " + intervalo
                            + " | acumulado: " + total
                            + (bstar != null ? " | estado " + bstar.estado() : ""));
                    intervalo.clear();
                    proximasMetricas = ahora + intervaloMetricas * 1000L;
                }
            }
        }
    }

    private static void responder(ZMQ.Socket frontend, byte[] cliente, String respuesta) {
        frontend.sendMore(cliente);
        frontend.sendMore("");
        frontend.send(respuesta);
    }

    private static void contar(Map<Tipo, Integer> intervalo, Map<Tipo, Integer> total, Tipo t) {
        intervalo.merge(t, 1, Integer::sum);
        total.merge(t, 1, Integer::sum);
    }

    /** "host:5556,host:5566" -> ["tcp://host:5556", "tcp://host:5566"]. */
    private static List<String> listaEndpoints(String valor) {
        List<String> l = new ArrayList<>();
        for (String parte : valor.split(",")) {
            String p = parte.trim();
            if (p.isEmpty()) {
                continue;
            }
            int dosPuntos = p.lastIndexOf(':');
            if (dosPuntos <= 0) {
                throw new IllegalArgumentException("--rmc debe tener la forma host:puerto[,host:puerto]");
            }
            Integer.parseInt(p.substring(dosPuntos + 1));
            l.add("tcp://" + p);
        }
        if (l.isEmpty()) {
            throw new IllegalArgumentException("--rmc no puede estar vacio");
        }
        return l;
    }
}
