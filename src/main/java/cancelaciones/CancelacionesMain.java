package cancelaciones;

import comun.Argumentos;
import comun.Protocolo;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.HashMap;
import java.util.Map;

/**
 * Servicio de Cancelaciones y Notificaciones. Capa de negocio, MAQUINA 2 (srb-negocio).
 *
 * <pre>
 *  GT primario / respaldo (PUSH) ──▶ PULL :5558 ──▶ REQ ──▶ Persistencia (LIBERAR)
 *                                                  └──▶ PUSH ──▶ cliente (PULL ip:puerto) : NOTIF
 * </pre>
 *
 * <ol>
 *   <li>Recibe {@code CANCELAR:<idSol>:<idReserva>:<ipCliente>:<puertoNotif>} del GT por un socket
 *       PULL (patron Pipeline, asincrono: el GT ya le respondio {@code ACEPTADA} al cliente).</li>
 *   <li>Ordena a la Persistencia {@code LIBERAR:<idSol>:<idReserva>} con un REQ y espera su respuesta.</li>
 *   <li>Envia al cliente {@code NOTIF:<idReserva>:CANCELADA} o {@code NOTIF:<idReserva>:RECHAZADA}
 *       por un socket PUSH conectado a la direccion que el cliente indico en su mensaje.</li>
 * </ol>
 *
 * <p>Si la Persistencia no responde a tiempo, reintenta la misma orden (con el mismo idSol, asi
 * que no se libera dos veces) antes de notificar el rechazo.</p>
 *
 * <pre>
 * Uso: java -cp srb.jar cancelaciones.CancelacionesMain [--puerto=5558] [--persistencia=host:5557]
 *                                                        [--timeout=5000] [--reintentos=3]
 * </pre>
 */
public class CancelacionesMain {

    public static void main(String[] args) {
        Argumentos a = new Argumentos(args);
        int puerto = a.entero("puerto", 5558);
        String persistencia = a.endpoint("persistencia", "localhost:5557");
        int timeout = a.entero("timeout", 5000);
        int reintentos = Math.max(1, a.entero("reintentos", 3));

        try (ZContext context = new ZContext()) {
            ZMQ.Socket entrada = context.createSocket(SocketType.PULL);
            entrada.bind("tcp://*:" + puerto);
            ZMQ.Socket socketPers = conectarPersistencia(context, persistencia, timeout);
            Map<String, ZMQ.Socket> clientes = new HashMap<>();   // un PUSH por direccion de cliente

            System.out.println("==========================================");
            System.out.println("[Cancelaciones] Recibiendo del GT en tcp://*:" + puerto + " (PULL)");
            System.out.println("[Cancelaciones] Persistencia en " + persistencia + " (REQ)");
            System.out.println("==========================================");

            while (!Thread.currentThread().isInterrupted()) {
                String mensaje = entrada.recvStr();
                if (mensaje == null) {
                    break;
                }
                String[] p = mensaje.split(Protocolo.SEP, -1);
                if (p.length != 5 || !p[0].equals(Protocolo.CANCELAR)) {
                    System.out.println("[Cancelaciones] Mensaje invalido, se descarta: " + mensaje);
                    continue;
                }
                String idSol = p[1];
                String idReserva = p[2];
                String destino = "tcp://" + p[3] + ":" + p[4];

                String orden = Protocolo.LIBERAR + Protocolo.SEP + idSol + Protocolo.SEP + idReserva;
                String respuesta = null;
                for (int intento = 1; intento <= reintentos && respuesta == null; intento++) {
                    socketPers.send(orden);
                    respuesta = socketPers.recvStr();
                    if (respuesta == null) {
                        System.out.println("[Cancelaciones] La Persistencia no respondio (intento " + intento + ")");
                        context.destroySocket(socketPers);
                        socketPers = conectarPersistencia(context, persistencia, timeout);
                    }
                }
                boolean cancelada = Protocolo.OK.equals(respuesta);
                String notif = Protocolo.notificacion(idReserva, cancelada);

                ZMQ.Socket push = clientes.computeIfAbsent(destino, d -> {
                    ZMQ.Socket s = context.createSocket(SocketType.PUSH);
                    s.setLinger(2000);
                    s.setSendTimeOut(2000);
                    s.connect(d);
                    return s;
                });
                boolean enviada = push.send(notif);
                System.out.println("[Cancelaciones] " + orden + " -> " + respuesta + " | " + notif
                        + (enviada ? " enviada a " : " NO se pudo enviar a ") + destino);
            }
        }
    }

    private static ZMQ.Socket conectarPersistencia(ZContext context, String endpoint, int timeout) {
        ZMQ.Socket s = context.createSocket(SocketType.REQ);
        s.setLinger(0);
        s.setReceiveTimeOut(timeout);
        s.connect(endpoint);
        return s;
    }
}
