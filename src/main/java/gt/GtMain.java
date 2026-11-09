package gt;

import comun.Argumentos;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

public class GtMain {

    public static void main(String[] args) {
        Argumentos a = new Argumentos(args);
        int puerto = a.entero("puerto", 5555);
        String rmc = a.endpoint("rmc", "localhost:5556");

        try (ZContext context = new ZContext()) {
            ZMQ.Socket socketClientes = context.createSocket(SocketType.REP);
            socketClientes.bind("tcp://*:" + puerto);

            ZMQ.Socket socketRmc = context.createSocket(SocketType.REQ);
            socketRmc.connect(rmc);

            System.out.println("==========================================");
            System.out.println("[GT] Escuchando clientes en tcp://*:" + puerto);
            System.out.println("[GT] Conectado al RMC en " + rmc);
            System.out.println("==========================================");

            long atendidas = 0;
            while (!Thread.currentThread().isInterrupted()) {
                String mensajeCliente = new String(socketClientes.recv(0), ZMQ.CHARSET);
                long inicio = System.nanoTime();

                socketRmc.send(mensajeCliente.getBytes(ZMQ.CHARSET), 0);
                String respuestaRmc = new String(socketRmc.recv(0), ZMQ.CHARSET); // espera síncrona

                socketClientes.send(respuestaRmc.getBytes(ZMQ.CHARSET), 0);
                atendidas++;
                double ms = (System.nanoTime() - inicio) / 1_000_000.0;
                System.out.printf("[GT] #%d %s -> %s (%.1f ms)%n", atendidas, mensajeCliente,
                        respuestaRmc.length() > 40 ? "JSON de eventos" : respuestaRmc, ms);
            }
        }
    }
}
