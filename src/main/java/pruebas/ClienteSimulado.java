package pruebas;

import cliente.ConexionGt;
import cliente.ErrorComunicacionException;
import cliente.ProtocoloSrb;
import cliente.ResultadoReserva;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cliente simulado que se ejecuta como tarea en un hilo del {@code ExecutorService}.
 *
 * <p>Cada cliente crea su propia {@link ConexionGt} (contexto ZeroMQ y socket REQ) dentro de {@link #call()},
 * y la usa y cierra en ese mismo hilo, ya que los sockets ZeroMQ no son seguros entre hilos.</p>
 *
 * <p>Las solicitudes se programan cada 60/rpm segundos, con un desfase inicial aleatorio y una variación
 * de ±10 % por envío para que los clientes no coincidan en el mismo instante. Como REQ/REP es síncrono,
 * cada cliente tiene a lo sumo una solicitud pendiente; si una respuesta se retrasa, el siguiente envío
 * se realiza al recibirla, sin acumular ráfagas.</p>
 */
final class ClienteSimulado implements Callable<ClienteSimulado.Resumen> {

    /** Resumen del cliente al finalizar; permite contrastar los conteos con el CSV. */
    record Resumen(String clienteId,
                   PerfilCliente perfil,
                   String hilo,
                   int conexionId,
                   long enviadas,
                   Map<TipoOperacion, Map<EstadoTransaccion, Long>> conteo,
                   long primerEnvioNanos,
                   long ultimoEnvioNanos) {
    }

    /** Sincronización del arranque simultáneo de los clientes. */
    static final class Arranque {
        final CountDownLatch listos;
        final CountDownLatch inicio = new CountDownLatch(1);
        volatile long t0Nanos;
        volatile long finNanos;

        Arranque(int clientes) {
            this.listos = new CountDownLatch(clientes);
        }
    }

    private final String clienteId;
    private final int numero;
    private final PerfilCliente perfil;
    private final ConfiguracionCarga cfg;
    private final List<SelectorOcurrencia.Ocurrencia> ocurrencias;
    private final EscritorCsv escritor;
    private final Arranque arranque;
    private final AtomicLong secuencia;
    private final AtomicInteger conexionesAbiertas;
    private final AtomicBoolean detener;

    ClienteSimulado(int numero, ConfiguracionCarga cfg, List<SelectorOcurrencia.Ocurrencia> ocurrencias,
                    EscritorCsv escritor, Arranque arranque, AtomicLong secuencia,
                    AtomicInteger conexionesAbiertas, AtomicBoolean detener) {
        this.numero = numero;
        this.clienteId = String.format("C%02d", numero);
        this.perfil = cfg.perfilDe(numero);
        this.cfg = cfg;
        this.ocurrencias = ocurrencias;
        this.escritor = escritor;
        this.arranque = arranque;
        this.secuencia = secuencia;
        this.conexionesAbiertas = conexionesAbiertas;
        this.detener = detener;
    }

    @Override
    public Resumen call() throws InterruptedException {
        // Generador aleatorio propio: reproducible con la misma semilla y no compartido entre hilos.
        Random aleatorio = new Random(cfg.semilla * 1_000 + numero);
        SelectorOcurrencia selector = new SelectorOcurrencia(ocurrencias, cfg.seleccion, aleatorio,
                (numero - 1) * Math.max(1, ocurrencias.size() / Math.max(1, cfg.clientes)));

        Map<TipoOperacion, Map<EstadoTransaccion, Long>> conteo = new EnumMap<>(TipoOperacion.class);
        for (TipoOperacion t : TipoOperacion.values()) {
            conteo.put(t, new EnumMap<>(EstadoTransaccion.class));
        }
        long enviadas = 0;
        long primerEnvio = 0;
        long ultimoEnvio = 0;

        ConexionGt conexion;
        try {
            conexion = new ConexionGt(cfg.host, cfg.puerto, cfg.timeoutMs);
        } finally {
            arranque.listos.countDown(); // se notifica incluso si falla, para no bloquear al generador
        }
        conexionesAbiertas.incrementAndGet();
        try {
            arranque.inicio.await(); // todos los clientes inician en el mismo instante t0
            long intervalo = 60_000_000_000L / cfg.rpm;
            long desfase = (long) (aleatorio.nextDouble() * intervalo);
            long fin = arranque.finNanos;

            for (long turno = 0; !detener.get(); turno++) {
                long variacion = (long) ((aleatorio.nextDouble() * 0.2 - 0.1) * intervalo);
                long programado = arranque.t0Nanos + desfase + turno * intervalo + variacion;
                if (programado >= fin) {
                    break;
                }
                if (!dormirHasta(programado)) {
                    break; // detención solicitada
                }

                TipoOperacion tipo = elegirOperacion(aleatorio);
                long ahora = System.nanoTime();
                if (enviadas == 0) {
                    primerEnvio = ahora;
                }
                ultimoEnvio = ahora;
                RegistroTransaccion r = (tipo == TipoOperacion.CONSULTA)
                        ? consultar(conexion)
                        : reservar(conexion, selector.siguiente(), elegirAsientos(aleatorio));
                escritor.registrar(r);
                enviadas++;
                conteo.get(tipo).merge(r.estado(), 1L, Long::sum);
            }
        } finally {
            conexion.close(); // se cierra en el mismo hilo que creó el socket
            conexionesAbiertas.decrementAndGet();
        }
        return new Resumen(clienteId, perfil, Thread.currentThread().getName(),
                System.identityHashCode(conexion), enviadas, conteo, primerEnvio, ultimoEnvio);
    }

    private TipoOperacion elegirOperacion(Random aleatorio) {
        if (perfil == PerfilCliente.MIXTO && aleatorio.nextInt(100) < cfg.pctReserva) {
            return TipoOperacion.RESERVA;
        }
        return TipoOperacion.CONSULTA;
    }

    private int elegirAsientos(Random aleatorio) {
        return cfg.asientosMin + aleatorio.nextInt(cfg.asientosMax - cfg.asientosMin + 1);
    }

    /** Envía una consulta. La latencia cubre solo el envío y la respuesta; el JSON se valida después. */
    private RegistroTransaccion consultar(ConexionGt conexion) {
        Instant marca = Instant.now();
        long inicio = System.nanoTime();
        EstadoTransaccion estado;
        long finRespuesta;
        try {
            String respuesta = conexion.enviar(ProtocoloSrb.CONSULTA);
            finRespuesta = System.nanoTime();
            ProtocoloSrb.parsearConsulta(respuesta); // valida el formato de la respuesta
            estado = EstadoTransaccion.OK;
        } catch (ErrorComunicacionException e) {
            finRespuesta = System.nanoTime();
            estado = EstadoTransaccion.desde(e.getTipo());
        }
        return new RegistroTransaccion(secuencia.incrementAndGet(), marca, clienteId, perfil,
                TipoOperacion.CONSULTA, (finRespuesta - inicio) / 1_000_000.0, estado, null, null, null);
    }

    /** Envía una reserva en un único intento; no se reintenta porque la operación no es idempotente. */
    private RegistroTransaccion reservar(ConexionGt conexion, SelectorOcurrencia.Ocurrencia destino, int cantidad) {
        String mensaje = ProtocoloSrb.mensajeReserva(destino.evento(), destino.numero(), cantidad);
        Instant marca = Instant.now();
        long inicio = System.nanoTime();
        EstadoTransaccion estado;
        long finRespuesta;
        try {
            String respuesta = conexion.enviar(mensaje);
            finRespuesta = System.nanoTime();
            estado = ResultadoReserva.desde(respuesta) == ResultadoReserva.EXITOSA
                    ? EstadoTransaccion.OK : EstadoTransaccion.RECHAZADO;
        } catch (ErrorComunicacionException e) {
            finRespuesta = System.nanoTime();
            estado = EstadoTransaccion.desde(e.getTipo());
        }
        return new RegistroTransaccion(secuencia.incrementAndGet(), marca, clienteId, perfil,
                TipoOperacion.RESERVA, (finRespuesta - inicio) / 1_000_000.0, estado,
                destino.evento(), destino.numero(), cantidad);
    }

    /**
     * Espera hasta el instante indicado en intervalos cortos para atender una solicitud de detención.
     *
     * @return false si se solicitó detener durante la espera
     */
    private boolean dormirHasta(long instanteNanos) throws InterruptedException {
        while (!detener.get()) {
            long restante = instanteNanos - System.nanoTime();
            if (restante <= 0) {
                return true;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(restante, 200_000_000L));
        }
        return false;
    }
}
