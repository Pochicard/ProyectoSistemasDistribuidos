package rmc;

import comun.Argumentos;
import comun.Protocolo;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * Servicio RMC (Reserva, Modificacion y Consulta). Capa de negocio, MAQUINA 2 (srb-negocio).
 *
 * <p>Se pueden arrancar varias replicas (p. ej. puertos 5556 y 5566). El GT reparte las
 * solicitudes entre ellas y, si una deja de responder, reenvia sus solicitudes a otra
 * (ver {@code gt.BalanceadorRmc}). Por eso el RMC no guarda estado propio: todo esta en la BD.</p>
 *
 * <p>Patron Request/Reply sincrono en los dos lados:</p>
 * <ul>
 *   <li>Hacia los GT: socket REP ({@code bind}). Acepta conexiones del GT primario y del de respaldo.</li>
 *   <li>Hacia la Persistencia: socket REQ con tiempo maximo de espera. Si la Persistencia no
 *       responde a tiempo, el RMC descarta ese socket (Lazy Pirate), crea uno nuevo y responde
 *       {@code ERROR} en lugar de quedarse bloqueado para siempre.</li>
 * </ul>
 *
 * <pre>
 * Uso: java -cp srb.jar rmc.RmcMain [--puerto=5556] [--persistencia=host:5557] [--timeout=5000] [--silencioso]
 *   --silencioso  no imprime una linea por solicitud (para pruebas de carga)
 * </pre>
 */
public class RmcMain {

    public static void main(String[] args) {
        Argumentos a = new Argumentos(args);
        int puerto = a.entero("puerto", 5556);
        String persistencia = a.endpoint("persistencia", "localhost:5557");
        int timeout = a.entero("timeout", 5000);
        boolean silencioso = a.tiene("silencioso");
        String nombre = "RMC:" + puerto;

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socketGt = context.createSocket(SocketType.REP);
            socketGt.bind("tcp://*:" + puerto);
            ZMQ.Socket socketPers = conectarPersistencia(context, persistencia, timeout);

            System.out.println("==========================================");
            System.out.println("[" + nombre + "] Escuchando a los GT en tcp://*:" + puerto);
            System.out.println("[" + nombre + "] Conectado a Persistencia en " + persistencia);
            System.out.println("==========================================");

            while (!Thread.currentThread().isInterrupted()) {
                String mensaje = socketGt.recvStr();
                if (mensaje == null) {
                    break;
                }
                String op = Protocolo.operacion(mensaje);
                String respuesta;
                if (op.equals(Protocolo.CONSULTA) || op.equals(Protocolo.RESERVA) || op.equals(Protocolo.MODIFICAR)) {
                    socketPers.send(mensaje);
                    respuesta = socketPers.recvStr();          // espera sincrona, con limite de tiempo
                    if (respuesta == null) {
                        System.out.println("[" + nombre + "] La Persistencia no respondio en " + timeout
                                + " ms; se reinicia el socket");
                        context.destroySocket(socketPers);
                        socketPers = conectarPersistencia(context, persistencia, timeout);
                        respuesta = Protocolo.error("La Persistencia no respondio");
                    }
                } else {
                    respuesta = Protocolo.error("Operacion no soportada por el RMC: " + op);
                }
                socketGt.send(respuesta);
                if (!silencioso) {
                    System.out.println("[" + nombre + "] " + mensaje + " -> "
                            + (respuesta.startsWith("{") ? "JSON de eventos" : respuesta));
                }
            }
        }
    }

    private static ZMQ.Socket conectarPersistencia(ZContext context, String endpoint, int timeout) {
        ZMQ.Socket s = context.createSocket(SocketType.REQ);
        s.setLinger(0);                  // al cerrarlo, descarta lo pendiente
        s.setReceiveTimeOut(timeout);    // recv() devuelve null si no hay respuesta a tiempo
        s.connect(endpoint);
        return s;
    }
}
