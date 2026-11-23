package cliente;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Canal REQ hacia el Gestor de Transacciones, con GT de respaldo (patrón Lazy Pirate).
 *
 * <p>Un contexto y un socket REQ por instancia, usados solo desde el hilo que los creó. Si una solicitud
 * no se envía o no recibe respuesta a tiempo, el socket se descarta y se crea otro hacia el siguiente GT.</p>
 */
public class ConexionGt implements AutoCloseable {

    /** Espera base y tope del backoff exponencial entre reintentos (ms). */
    private static final long ESPERA_BASE_MS = 200;
    private static final long ESPERA_TOPE_MS = 2000;

    private final List<String> endpoints;
    private final int timeoutMs;
    private final ZContext contexto;
    private final Thread hiloPropietario;
    private ZMQ.Socket socket;
    private int actual;
    private int cambiosDeGt;

    /** Conexión a un único GT. */
    public ConexionGt(String host, int puerto, int timeoutMs) {
        this(List.of(host + ":" + puerto), timeoutMs);
    }

    // direcciones: GT en orden de preferencia (host:puerto), primero el primario.
    public ConexionGt(List<String> direcciones, int timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("El tiempo límite debe ser positivo");
        }
        if (direcciones.isEmpty()) {
            throw new IllegalArgumentException("Debe indicarse al menos un GT");
        }
        List<String> lista = new ArrayList<>();
        for (String d : direcciones) {
            lista.add("tcp://" + d.trim());
        }
        this.endpoints = List.copyOf(lista);
        this.timeoutMs = timeoutMs;
        this.contexto = new ZContext();
        this.hiloPropietario = Thread.currentThread();
        this.socket = crearSocket();
    }

    /** Convierte "host:puerto,host:puerto" en una lista, validando cada dirección. */
    public static List<String> parsearDirecciones(String texto) {
        List<String> lista = new ArrayList<>();
        for (String parte : texto.split(",")) {
            String d = parte.trim();
            if (d.isEmpty()) {
                continue;
            }
            int dosPuntos = d.lastIndexOf(':');
            if (dosPuntos <= 0 || dosPuntos == d.length() - 1) {
                throw new IllegalArgumentException("Dirección inválida: " + d + " (use host:puerto)");
            }
            int puerto;
            try {
                puerto = Integer.parseInt(d.substring(dosPuntos + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Puerto inválido en " + d);
            }
            if (puerto < 1 || puerto > 65535) {
                throw new IllegalArgumentException("Puerto fuera de rango en " + d);
            }
            lista.add(d);
        }
        if (lista.isEmpty()) {
            throw new IllegalArgumentException("Debe indicarse al menos un GT");
        }
        return lista;
    }

    private ZMQ.Socket crearSocket() {
        ZMQ.Socket s = contexto.createSocket(SocketType.REQ);
        s.setLinger(0);                     // al cerrar se descartan mensajes pendientes
        s.setReceiveTimeOut(timeoutMs);     // sin tiempo límite, recv() bloquearía indefinidamente
        s.setSendTimeOut(timeoutMs);
        // Solo encolar hacia conexiones ya establecidas: si el GT está apagado, send() falla
        // (NO_ENVIADO) en lugar de dejar el mensaje en una cola local. En JeroMQ 0.5.4 el valor de
        // setImmediate está invertido respecto a libzmq: false equivale a ZMQ_IMMEDIATE=1.
        s.setImmediate(false);
        s.connect(endpoints.get(actual));
        return s;
    }

    /**
     * Envía un mensaje al GT actual y espera la respuesta (un solo intento). Si falla, la conexión
     * queda apuntando al siguiente GT de la lista.
     */
    public String enviar(String mensaje) throws ErrorComunicacionException {
        verificarHilo();
        String destino = endpoints.get(actual);

        if (!socket.send(mensaje.getBytes(ZMQ.CHARSET), 0)) {
            pasarAlSiguienteGt();
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.NO_ENVIADO,
                    "No se pudo enviar la solicitud a " + destino + " en " + timeoutMs + " ms.");
        }
        byte[] respuesta = socket.recv(0);  // null si vence el tiempo límite
        if (respuesta == null) {
            pasarAlSiguienteGt();
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.SIN_RESPUESTA,
                    "No llegó respuesta de " + destino + " en " + timeoutMs + " ms.");
        }
        return new String(respuesta, ZMQ.CHARSET);
    }

    /**
     * Envía con reintentos alternando los GT, con espera exponencial aleatoria entre intentos (backoff con
     * jitter). Solo para mensajes idempotentes: consultas o mensajes con idSol.
     */
    public String enviarConReintentos(String mensaje, int intentos, Consumer<String> aviso)
            throws ErrorComunicacionException {
        ErrorComunicacionException ultimo = null;
        for (int i = 1; i <= Math.max(1, intentos); i++) {
            if (i > 1) {
                esperarAntesDeReintentar(i - 1);
            }
            try {
                return enviar(mensaje);
            } catch (ErrorComunicacionException e) {
                if (e.getTipo() == ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA) {
                    throw e;
                }
                ultimo = e;
                if (aviso != null) {
                    aviso.accept("Intento " + i + "/" + intentos + " fallido [" + e.getTipo() + "]: "
                            + e.getMessage() + (i < intentos ? " Se reintenta en " + getEndpoint() + "." : ""));
                }
            }
        }
        throw ultimo;
    }

    /** Backoff exponencial con jitter: espera aleatoria en [0, min(tope, base × 2^reintento)]. */
    private static void esperarAntesDeReintentar(int reintento) {
        long limite = Math.min(ESPERA_TOPE_MS, ESPERA_BASE_MS << Math.min(reintento, 10));
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(limite + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Cierra el socket REQ inutilizable y crea uno nuevo hacia el siguiente GT. */
    private void pasarAlSiguienteGt() {
        socket.close();
        if (endpoints.size() > 1) {
            actual = (actual + 1) % endpoints.size();
            cambiosDeGt++;
        }
        socket = crearSocket();
    }

    private void verificarHilo() {
        if (Thread.currentThread() != hiloPropietario) {
            throw new IllegalStateException(
                    "ConexionGt no puede compartirse entre hilos: cada hilo debe crear la suya");
        }
    }

    /** GT al que se enviará la próxima solicitud. */
    public String getEndpoint() {
        return endpoints.get(actual);
    }

    public List<String> getEndpoints() {
        return endpoints;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    /** Veces que la conexión pasó de un GT a otro por una falla. */
    public int getCambiosDeGt() {
        return cambiosDeGt;
    }

    @Override
    public void close() {
        contexto.close();
    }
}
