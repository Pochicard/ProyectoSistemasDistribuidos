package rmc;

import comun.Argumentos;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * Servicio RMC (Reserva, Modificación y Consulta). Capa de negocio, MÁQUINA 2.
 *
 * <p>Patrón: <b>Request/Reply síncrono</b> en los dos lados.</p>
 * <ul>
 *   <li>Hacia el GT: socket REP (recibe una solicitud, responde, recibe la siguiente).</li>
 *   <li>Hacia Persistencia: socket REQ. Tras enviar queda <i>bloqueado</i> en recv() hasta
 *       que la Persistencia confirma; solo entonces responde al GT.</li>
 * </ul>
 *
 * <pre>
 * Uso: java -cp srb.jar rmc.RmcMain [--puerto=5556] [--persistencia=host:5557]
 * </pre>
 */
public class RmcMain {

    public static void main(String[] args) {
        Argumentos a = new Argumentos(args);
        int puerto = a.entero("puerto", 5556);
        String persistencia = a.endpoint("persistencia", "localhost:5557");

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socketGt = context.createSocket(SocketType.REP);
            socketGt.bind("tcp://*:" + puerto);

            ZMQ.Socket socketPersistencia = context.createSocket(SocketType.REQ);
            socketPersistencia.connect(persistencia);

            System.out.println("==========================================");
            System.out.println("[RMC] Escuchando al GT en tcp://*:" + puerto);
            System.out.println("[RMC] Conectado a Persistencia en " + persistencia);
            System.out.println("==========================================");

            while (!Thread.currentThread().isInterrupted()) {
                String mensajeGt = new String(socketGt.recv(0), ZMQ.CHARSET);
                System.out.println("[RMC] Recibido del GT: " + mensajeGt);

                String respuesta;
                if (mensajeGt.startsWith("CONSULTA") || mensajeGt.startsWith("RESERVA")) {
                    socketPersistencia.send(mensajeGt.getBytes(ZMQ.CHARSET), 0);
                    respuesta = new String(socketPersistencia.recv(0), ZMQ.CHARSET); // espera síncrona
                } else {
                    respuesta = "ERROR: Operación no soportada por el RMC";
                }

                socketGt.send(respuesta.getBytes(ZMQ.CHARSET), 0);
                System.out.println("[RMC] Respuesta al GT: "
                        + (respuesta.length() > 60 ? "JSON con " + contarEventos(respuesta) + " eventos" : respuesta));
            }
        }
    }

    private static int contarEventos(String json) {
        return json.split("\"id\"", -1).length - 1;
    }
}
