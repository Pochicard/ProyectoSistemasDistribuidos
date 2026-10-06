package gt;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

public class GtMain {
    private static final int PUERTO_CLIENTES = 5555;

    private static final String RMC_HOST = "localhost";
    private static final int RMC_PUERTO = 5556;

    public static void main(String[] args) {
        try (ZContext context = new ZContext()) {
            ZMQ.Socket socketClientes = context.createSocket(SocketType.REP);
            socketClientes.bind("tcp://*:" + PUERTO_CLIENTES);

            ZMQ.Socket socketRmc = context.createSocket(SocketType.REQ);
            socketRmc.connect("tcp://" + RMC_HOST + ":" + RMC_PUERTO);

            System.out.println("==========================================");
            System.out.println("[Gestor de Transacciones] Escuchando Clientes en el puerto " + PUERTO_CLIENTES + "...");
            System.out.println("[Gestor de Transacciones] Conectado a RMC en " + RMC_HOST + ":" + RMC_PUERTO);
            System.out.println("==========================================");

            while (!Thread.currentThread().isInterrupted()) {
                byte[] request = socketClientes.recv(0);
                String mensajeCliente = new String(request, ZMQ.CHARSET);
                System.out.println("[GT] Petición recibida del Cliente: " + mensajeCliente);

                socketRmc.send(mensajeCliente.getBytes(ZMQ.CHARSET), 0);

                byte[] replyRmc = socketRmc.recv(0);
                String respuestaRmc = new String(replyRmc, ZMQ.CHARSET);

                socketClientes.send(respuestaRmc.getBytes(ZMQ.CHARSET), 0);
                System.out.println("[GT] Respuesta enviada al Cliente correctamente.");
            }
        }
    }
}
