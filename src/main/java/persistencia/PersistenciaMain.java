package persistencia;

import comun.Argumentos;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PersistenciaMain {

    public static void main(String[] args) {
        Argumentos a = new Argumentos(args);
        int puerto = a.entero("puerto", 5557);
        Path dirDatos = Paths.get(a.texto("datos", "datos"));
        Path carga = a.tiene("carga") ? Paths.get(a.texto("carga", null)) : null;

        BaseDatos bd = new BaseDatos(dirDatos, a.tiene("reiniciar"), carga);

        System.out.println("==========================================");
        System.out.println("[Persistencia] Base de datos SQLite: " + bd.getArchivoBd().toAbsolutePath());
        System.out.println("[Persistencia] ESTADO INICIAL DE LA BD:");
        System.out.print(bd.tablaEstado());
        System.out.println("[Persistencia] " + bd.resumen());
        System.out.println("==========================================");

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REP);
            socket.bind("tcp://*:" + puerto);
            System.out.println("[Persistencia] Escuchando al RMC en tcp://*:" + puerto);

            while (!Thread.currentThread().isInterrupted()) {
                String mensaje = new String(socket.recv(0), ZMQ.CHARSET);
                String respuesta;
                try {
                    respuesta = atender(bd, mensaje);
                } catch (RuntimeException e) {
                    respuesta = "ERROR:" + e.getMessage();
                }
                socket.send(respuesta.getBytes(ZMQ.CHARSET), 0);
            }
        }
    }

    private static String atender(BaseDatos bd, String mensaje) {
        if (mensaje.equals("CONSULTA") || mensaje.startsWith("CONSULTA:")) {
            System.out.println("[Persistencia] CONSULTA -> se envían todos los eventos");
            return bd.consultarJson();
        }
        if (mensaje.startsWith("RESERVA:")) {
            String[] p = mensaje.split(":");
            if (p.length != 4) {
                return "ERROR:Formato esperado RESERVA:<evento>:<ocurrencia>:<cantidad>";
            }
            String id = p[1];
            int ocurrencia = Integer.parseInt(p[2]);
            int cantidad = Integer.parseInt(p[3]);
            boolean ok = bd.hacerReserva(id, ocurrencia, cantidad);
            String respuesta = ok ? "OK" : "RECHAZADO";
            System.out.println("[Persistencia] " + mensaje + " -> " + respuesta + " | " + bd.lineaEvento(id));
            System.out.println("[Persistencia] " + bd.resumen() + " (COMMIT en SQLite)");
            return respuesta;
        }
        return "ERROR:Comando no reconocido";
    }
}
