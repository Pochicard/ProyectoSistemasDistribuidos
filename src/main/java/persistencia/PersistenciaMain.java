package persistencia;

import comun.Argumentos;
import comun.Protocolo;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Servicio de Persistencia (capa de almacenamiento). Se ejecuta en la MAQUINA 3 (srb-datos).
 *
 * <h2>Arquitectura interna: servidor multihilo de ZeroMQ</h2>
 * <pre>
 *  RMC 1, RMC 2, Cancelaciones (REQ) ──tcp──▶ ROUTER :5557 ══ proxy ══ DEALER inproc ──▶ N hilos (REP)
 *                                                                                      cada uno con su
 *                                                                                      conexion SQLite
 * </pre>
 * El ROUTER recibe de todos los clientes y el proxy reparte las solicitudes entre los hilos
 * trabajadores. Las consultas se atienden en paralelo (SQLite en modo WAL) y las escrituras se
 * ordenan en la BD (ver {@link BaseDatos}). Para quien llama sigue siendo Request/Reply sincrono.
 *
 * <pre>
 * Uso: java -cp srb.jar persistencia.PersistenciaMain [--puerto=5557] [--datos=./datos] [--hilos=4]
 *                                                    [--reiniciar] [--carga=archivo.json] [--silencioso]
 *   --puerto     puerto donde escucha (defecto 5557)
 *   --datos      carpeta de la base de datos SQLite srb.db (defecto ./datos)
 *   --hilos      hilos trabajadores (defecto 4)
 *   --reiniciar  borra todo y vuelve a la carga inicial (50 eventos)
 *   --carga      carga inicial desde un JSON (p. ej. el caso de prueba del profesor)
 *   --silencioso no imprime una linea por solicitud (para pruebas de carga)
 * </pre>
 * Mensajes: ver {@link Protocolo}. Ademas de los del cliente, atiende {@code LIBERAR:<idSol>:<idReserva>}.
 */
public class PersistenciaMain {

    private static final String BACKEND = "inproc://persistencia-hilos";
    private static boolean silencioso;

    public static void main(String[] args) {
        Argumentos a = new Argumentos(args);
        int puerto = a.entero("puerto", 5557);
        int hilos = Math.max(1, a.entero("hilos", 4));
        silencioso = a.tiene("silencioso");
        Path dirDatos = Paths.get(a.texto("datos", "datos"));
        Path carga = a.tiene("carga") ? Paths.get(a.texto("carga", null)) : null;

        try (BaseDatos bd = new BaseDatos(dirDatos)) {
            bd.prepararDatos(a.tiene("reiniciar"), carga);
            System.out.println("==========================================");
            System.out.println("[Persistencia] Base de datos SQLite: " + bd.getArchivoBd().toAbsolutePath());
            System.out.println("[Persistencia] ESTADO INICIAL DE LA BD:");
            System.out.print(bd.tablaEstado());
            System.out.println("[Persistencia] " + bd.resumen());
            System.out.println("==========================================");
        }

        try (ZContext context = new ZContext()) {
            ZMQ.Socket frontend = context.createSocket(SocketType.ROUTER);
            frontend.bind("tcp://*:" + puerto);
            ZMQ.Socket backend = context.createSocket(SocketType.DEALER);
            backend.bind(BACKEND);

            for (int i = 1; i <= hilos; i++) {
                int n = i;
                Thread t = new Thread(() -> trabajador(context, dirDatos, n), "persistencia-" + n);
                t.setDaemon(true);
                t.start();
            }
            System.out.println("[Persistencia] Escuchando en tcp://*:" + puerto + " con " + hilos + " hilos");

            ZMQ.proxy(frontend, backend, null);   // bloquea: reparte solicitudes y devuelve respuestas
        }
    }

    /** Un hilo trabajador: su propio socket REP (inproc) y su propia conexion a SQLite. */
    private static void trabajador(ZContext context, Path dirDatos, int n) {
        ZMQ.Socket socket = context.createSocket(SocketType.REP);
        socket.connect(BACKEND);
        try (BaseDatos bd = new BaseDatos(dirDatos)) {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] datos = socket.recv(0);
                if (datos == null) {
                    break;   // el contexto se cerro
                }
                String mensaje = new String(datos, ZMQ.CHARSET);
                String respuesta;
                try {
                    respuesta = atender(bd, mensaje, n);
                } catch (RuntimeException e) {
                    // Un REP siempre debe responder; si no, quien llamo queda bloqueado.
                    respuesta = Protocolo.error(e.getMessage());
                    System.out.println("[Persistencia h" + n + "] " + mensaje + " -> " + respuesta);
                }
                socket.send(respuesta.getBytes(ZMQ.CHARSET), 0);
            }
        } catch (org.zeromq.ZMQException e) {
            // el contexto se cerro al terminar el proceso
        }
    }

    private static String atender(BaseDatos bd, String mensaje, int n) {
        String[] p = mensaje.split(Protocolo.SEP, -1);
        String op = p[0];
        BaseDatos.Resultado r;
        switch (op) {
            case Protocolo.CONSULTA:
                if (!silencioso) {
                    System.out.println("[Persistencia h" + n + "] CONSULTA -> se envian todos los eventos");
                }
                return bd.consultarJson();
            case Protocolo.RESERVA:
                if (p.length == 4) {          // formato de la 1.a entrega: RESERVA:E1:1:2
                    r = bd.reservar(null, p[1], entero(p[2]), entero(p[3]));
                } else if (p.length == 5) {   // RESERVA:<idSol>:E1:1:2
                    r = bd.reservar(p[1], p[2], entero(p[3]), entero(p[4]));
                } else {
                    return Protocolo.error("Formato esperado RESERVA:<idSol>:<evento>:<ocurrencia>:<cantidad>");
                }
                break;
            case Protocolo.MODIFICAR:
                if (p.length != 5) {
                    return Protocolo.error("Formato esperado MODIFICAR:<idSol>:<idReserva>:<ocurrencia>:<cantidad>");
                }
                r = bd.modificar(p[1], largo(p[2]), entero(p[3]), entero(p[4]));
                break;
            case Protocolo.LIBERAR:
                if (p.length != 3) {
                    return Protocolo.error("Formato esperado LIBERAR:<idSol>:<idReserva>");
                }
                r = bd.liberar(p[1], largo(p[2]));
                break;
            default:
                return Protocolo.error("Comando no reconocido: " + op);
        }
        if (silencioso) {
            return r.respuesta();
        }
        System.out.println("[Persistencia h" + n + "] " + mensaje + " -> " + r.respuesta()
                + (r.duplicada() ? "  (reintento: se devuelve la respuesta guardada, sin repetir)" : "")
                + (r.evento() != null ? " | " + bd.lineaEvento(r.evento()) : ""));
        System.out.println("[Persistencia h" + n + "] " + bd.resumen() + " (COMMIT en SQLite)");
        return r.respuesta();
    }

    private static int entero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Se esperaba un numero y llego: " + s);
        }
    }

    private static long largo(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Se esperaba un numero de reserva y llego: " + s);
        }
    }
}
