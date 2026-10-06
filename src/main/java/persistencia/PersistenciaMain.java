package persistencia;

import com.google.gson.Gson;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

public class PersistenciaMain {
    public static void main(String[] args) {
        BaseDatos bd = new BaseDatos();
        Gson gson = new Gson();

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socket = context.createSocket(SocketType.REP);
            socket.bind("tcp://*:5557"); // Puerto expuesto para el Servicio RMC
            System.out.println("==========================================");
            System.out.println("[Persistencia] Servicio listo en el puerto 5557...");
            System.out.println("==========================================");

            while (!Thread.currentThread().isInterrupted()) {
                byte[] request = socket.recv(0);
                String mensaje = new String(request, ZMQ.CHARSET);
                System.out.println("[Persistencia] Petición recibida: " + mensaje);

                String respuesta = "";
                if (mensaje.startsWith("CONSULTA")) {
                    respuesta = gson.toJson(bd.getEventos());
                } else if (mensaje.startsWith("RESERVA")) {
                    String[] partes = mensaje.split(":");
                    String idEvento = partes[1];
                    int ocurrencia = Integer.parseInt(partes[2]);
                    int cantidad = Integer.parseInt(partes[3]);

                    boolean exito = bd.hacerReserva(idEvento, ocurrencia, cantidad);
                    respuesta = exito ? "OK" : "RECHAZADO";
                } else {
                    respuesta = "ERROR:Comando no reconocido";
                }

                socket.send(respuesta.getBytes(ZMQ.CHARSET), 0);
            }
        }
    }
}