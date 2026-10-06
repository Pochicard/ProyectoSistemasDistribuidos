package rmc;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

public class RmcMain {
    private static final String PERSISTENCIA_HOST = "localhost";
    private static final int PERSISTENCIA_PUERTO = 5557;

    private static final int RMC_PUERTO_ESCUCHA = 5556;

    public static void main(String[] args) {
        try (ZContext context = new ZContext()) {
            ZMQ.Socket socketGt = context.createSocket(SocketType.REP);
            socketGt.bind("tcp://*:" + RMC_PUERTO_ESCUCHA);

            ZMQ.Socket socketPersistencia = context.createSocket(SocketType.REQ);
            socketPersistencia.connect("tcp://" + PERSISTENCIA_HOST + ":" + PERSISTENCIA_PUERTO);

            System.out.println("==========================================");
            System.out.println("[Servicio RMC] Escuchando al GT en el puerto " + RMC_PUERTO_ESCUCHA + "...");
            System.out.println("[Servicio RMC] Conectado a Persistencia en " + PERSISTENCIA_HOST + ":" + PERSISTENCIA_PUERTO);
            System.out.println("==========================================");

            while (!Thread.currentThread().isInterrupted()) {
                byte[] request = socketGt.recv(0);
                String mensajeGt = new String(request, ZMQ.CHARSET);
                System.out.println("[Servicio RMC] Petición recibida del GT: " + mensajeGt);

                String respuestaPersistencia = "";

                if (mensajeGt.startsWith("CONSULTA")) {socketPersistencia.send(mensajeGt.getBytes(ZMQ.CHARSET), 0);
                    byte[] reply = socketPersistencia.recv(0);
                    respuestaPersistencia = new String(reply, ZMQ.CHARSET);

                } else if (mensajeGt.startsWith("RESERVA")) {
                    socketPersistencia.send(mensajeGt.getBytes(ZMQ.CHARSET), 0);
                    byte[] reply = socketPersistencia.recv(0);
                    respuestaPersistencia = new String(reply, ZMQ.CHARSET);

                } else {
                    respuestaPersistencia = "ERROR: Operación no soportada por el Servicio RMC";
                }
                socketGt.send(respuestaPersistencia.getBytes(ZMQ.CHARSET), 0);
                System.out.println("[Servicio RMC] Respuesta enviada al GT: " + (respuestaPersistencia.length() > 60 ? "JSON con datos" : respuestaPersistencia));
            }
        }
    }
}