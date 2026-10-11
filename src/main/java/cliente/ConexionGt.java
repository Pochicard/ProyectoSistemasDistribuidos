package cliente;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * Canal síncrono REQ hacia el Gestor de Transacciones (GT, socket REP en el puerto 5555).
 *
 * <p><b>Reglas de uso:</b></p>
 * <ul>
 *   <li>Una instancia = un contexto ZeroMQ + un socket REQ. Los sockets ZeroMQ NO son
 *       seguros entre hilos, por eso esta clase solo puede usarse desde el hilo que la creó
 *       (se verifica en tiempo de ejecución). El generador de carga creará una por hilo.</li>
 *   <li>REQ impone la alternancia estricta send → recv → send → recv. Si un recv vence por
 *       tiempo límite, el socket queda "esperando respuesta" y no admite otro send. La solución
 *       recomendada por la guía de ZeroMQ (patrón <i>Lazy Pirate</i>) es cerrar ese socket y
 *       abrir uno nuevo; eso es lo que hace {@link #reiniciarSocket()}.</li>
 * </ul>
 */
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
        // Al cerrar, descartar mensajes pendientes en vez de bloquear la salida del proceso.
        s.setLinger(0);
        // Sin estas dos opciones, send/recv bloquean indefinidamente si el GT no responde.
        s.setReceiveTimeOut(timeoutMs);
        s.setSendTimeOut(timeoutMs);
        // Solo encolar mensajes hacia conexiones TCP ya establecidas. Así, si el GT está
        // apagado, send() falla tras el tiempo límite (NO_ENVIADO) en lugar de dejar el mensaje
        // en una cola local, que haría pensar que salió.
        // OJO: en JeroMQ 0.5.4 el booleano de setImmediate está INVERTIDO respecto a libzmq
        // (lo advierte el Javadoc de setDelayAttachOnConnect). false = esperar la conexión.
        // Verificado: con GT apagado, setImmediate(true) -> send()=true; false -> send()=false.
        s.setImmediate(false);
        s.connect(endpoint);
        return s;
    }

    /**
     * Envía un mensaje y espera la respuesta (una ida y vuelta REQ/REP completa).
     *
     * @return la respuesta del GT como texto UTF-8
     * @throws ErrorComunicacionException NO_ENVIADO si no hay conexión, SIN_RESPUESTA si vence el tiempo
     */
    public String enviar(String mensaje) throws ErrorComunicacionException {
        verificarHilo();

        boolean enviado = socket.send(mensaje.getBytes(ZMQ.CHARSET), 0);
        if (!enviado) {
            reiniciarSocket();
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.NO_ENVIADO,
                    "No se pudo enviar la solicitud a " + endpoint + " en " + timeoutMs
                            + " ms. ¿Está encendido el Gestor de Transacciones?");
        }

        byte[] respuesta = socket.recv(0); // devuelve null si vence el tiempo límite
        if (respuesta == null) {
            reiniciarSocket();
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.SIN_RESPUESTA,
                    "No llegó respuesta de " + endpoint + " en " + timeoutMs + " ms.");
        }
        return new String(respuesta, ZMQ.CHARSET);
    }

    /** Patrón Lazy Pirate: descarta el socket REQ "atascado" y crea uno nuevo. */
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

    /** Cierra el socket y el contexto. Gracias a linger=0 no se queda esperando. */
    @Override
    public void close() {
        contexto.close();
    }
}
