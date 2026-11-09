package cliente;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;


public class ConexionGt implements AutoCloseable {

    private final String endpoint;
    private final int timeoutMs;
    private final ZContext contexto;
    private final Thread hiloPropietario;
    private ZMQ.Socket socket;

    /**
     * @param host      host o IP del GT (p. ej. "localhost")
     * @param puerto    puerto del GT (5555 por defecto en el proyecto)
     * @param timeoutMs tiempo máximo de espera, en milisegundos, para enviar y para recibir
     */
    public ConexionGt(String host, int puerto, int timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("El tiempo límite debe ser positivo");
        }
        this.endpoint = "tcp://" + host + ":" + puerto;
        this.timeoutMs = timeoutMs;
        this.contexto = new ZContext();
        this.hiloPropietario = Thread.currentThread();
        this.socket = crearSocket();
    }

    private ZMQ.Socket crearSocket() {
        ZMQ.Socket s = contexto.createSocket(SocketType.REQ);
        s.setLinger(0);
        s.setReceiveTimeOut(timeoutMs);
        s.setSendTimeOut(timeoutMs);
        s.setImmediate(false);
        s.connect(endpoint);
        return s;
    }

    public String enviar(String mensaje) throws ErrorComunicacionException {
        verificarHilo();

        boolean enviado = socket.send(mensaje.getBytes(ZMQ.CHARSET), 0);
        if (!enviado) {
            reiniciarSocket();
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.NO_ENVIADO,
                    "No se pudo enviar la solicitud a " + endpoint + " en " + timeoutMs
                            + " ms. ¿Está encendido el Gestor de Transacciones?");
        }

        byte[] respuesta = socket.recv(0);
        if (respuesta == null) {
            reiniciarSocket();
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.SIN_RESPUESTA,
                    "No llegó respuesta de " + endpoint + " en " + timeoutMs + " ms.");
        }
        return new String(respuesta, ZMQ.CHARSET);
    }

    private void reiniciarSocket() {
        socket.close(); // en JeroMQ 0.5.x close() también lo retira del ZContext
        socket = crearSocket();
    }

    private void verificarHilo() {
        if (Thread.currentThread() != hiloPropietario) {
            throw new IllegalStateException(
                    "ConexionGt no puede compartirse entre hilos: cada hilo debe crear la suya");
        }
    }

    public String getEndpoint() {
        return endpoint;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    @Override
    public void close() {
        contexto.close();
    }
}
