package cliente;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Hilo que recibe las notificaciones de cancelación con un socket PULL (el servicio usa PUSH). El contexto
 * y el socket se crean, usan y cierran dentro de ese hilo.
 */
public final class ReceptorNotificaciones implements AutoCloseable {

    private static final int ESPERA_MS = 500;

    private final int puerto;
    private final BiConsumer<ProtocoloSrb.Notificacion, String> oyente;
    private final Thread hilo;
    private final CountDownLatch iniciado = new CountDownLatch(1);
    private volatile boolean detener;
    private volatile RuntimeException errorInicio;

    // Abre el puerto e inicia el hilo receptor. El oyente se ejecuta en ese hilo.
    public ReceptorNotificaciones(int puerto, BiConsumer<ProtocoloSrb.Notificacion, String> oyente) {
        this.puerto = puerto;
        this.oyente = oyente;
        this.hilo = new Thread(this::recibir, "notificaciones-" + puerto);
        this.hilo.setDaemon(true);
        this.hilo.start();
        try {
            iniciado.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (errorInicio != null) {
            throw errorInicio;
        }
    }

    private void recibir() {
        try (ZContext contexto = new ZContext()) {
            ZMQ.Socket pull = contexto.createSocket(SocketType.PULL);
            pull.setReceiveTimeOut(ESPERA_MS); // permite revisar periódicamente la orden de detener
            try {
                pull.bind("tcp://*:" + puerto);
            } catch (ZMQException e) {
                errorInicio = new IllegalStateException(
                        "No se pudo abrir el puerto de notificaciones " + puerto
                                + " (probablemente lo usa otro programa o cliente)", e);
                return;
            } finally {
                iniciado.countDown();
            }
            while (!detener) {
                String mensaje = pull.recvStr();
                if (mensaje == null) {
                    continue;
                }
                oyente.accept(ProtocoloSrb.parsearNotificacion(mensaje), mensaje);
            }
        }
    }

    public int getPuerto() {
        return puerto;
    }

    /** Detiene el hilo receptor y libera el puerto. */
    @Override
    public void close() {
        detener = true;
        try {
            hilo.join(2 * ESPERA_MS + 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
