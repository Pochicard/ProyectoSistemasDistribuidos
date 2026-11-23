package pruebas;

import cliente.ConexionGt;
import cliente.ErrorComunicacionException;
import cliente.ProtocoloSrb;
import cliente.ResultadoReserva;

import java.time.Instant;
import java.util.ArrayList;
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
 * Cliente simulado: una tarea del {@code ExecutorService} con su propia {@link ConexionGt}, creada, usada y
 * cerrada en el mismo hilo (los sockets ZeroMQ no son seguros entre hilos).
 *
 * <p>Envía cada 60/rpm segundos con desfase inicial y variación de ±10 %. Los clientes mixtos reservan,
 * modifican y cancelan sus propias reservas. No reintenta: un fallo se registra como error técnico.</p>
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
    private final Map<String, Integer> ocurrenciasPorEvento;
    private final EscritorCsv escritor;
    private final RegistroReservas registro;
    private final Arranque arranque;
    private final AtomicLong secuencia;
    private final AtomicInteger conexionesAbiertas;
    private final AtomicBoolean detener;
    /** Reservas propias activas; solo las usa este hilo. */
    private final List<Long> misReservas = new ArrayList<>();

    ClienteSimulado(int numero, ConfiguracionCarga cfg, List<SelectorOcurrencia.Ocurrencia> ocurrencias,
                    Map<String, Integer> ocurrenciasPorEvento, EscritorCsv escritor, RegistroReservas registro,
                    Arranque arranque, AtomicLong secuencia, AtomicInteger conexionesAbiertas, AtomicBoolean detener) {
        this.numero = numero;
        this.clienteId = String.format("C%02d", numero);
        this.perfil = cfg.perfilDe(numero);
        this.cfg = cfg;
        this.ocurrencias = ocurrencias;
        this.ocurrenciasPorEvento = ocurrenciasPorEvento;
        this.escritor = escritor;
        this.registro = registro;
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
            conexion = new ConexionGt(cfg.gts, cfg.timeoutMs);
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

                long ahora = System.nanoTime();
                if (enviadas == 0) {
                    primerEnvio = ahora;
                }
                ultimoEnvio = ahora;
                RegistroTransaccion r = ejecutar(conexion, elegirOperacion(aleatorio), selector, aleatorio);
                escritor.registrar(r);
                enviadas++;
                conteo.get(r.tipo()).merge(r.estado(), 1L, Long::sum);
            }
        } finally {
            conexion.close(); // se cierra en el mismo hilo que creó el socket
            conexionesAbiertas.decrementAndGet();
        }
        return new Resumen(clienteId, perfil, Thread.currentThread().getName(),
                System.identityHashCode(conexion), enviadas, conteo, primerEnvio, ultimoEnvio);
    }

    private TipoOperacion elegirOperacion(Random aleatorio) {
        if (perfil != PerfilCliente.MIXTO) {
            return TipoOperacion.CONSULTA;
        }
        int n = aleatorio.nextInt(100);
        if (n < cfg.pctReserva) {
            return TipoOperacion.RESERVA;
        }
        if (n < cfg.pctReserva + cfg.pctModificacion) {
            return TipoOperacion.MODIFICACION;
        }
        if (n < cfg.pctReserva + cfg.pctModificacion + cfg.pctCancelacion) {
            return TipoOperacion.CANCELACION;
        }
        return TipoOperacion.CONSULTA;
    }

    /** Sin reservas propias activas, una modificación o cancelación se reemplaza por una reserva. */
    private RegistroTransaccion ejecutar(ConexionGt conexion, TipoOperacion tipo, SelectorOcurrencia selector,
                                         Random aleatorio) {
        switch (tipo) {
            case CONSULTA:
                return consultar(conexion);
            case MODIFICACION:
                if (!misReservas.isEmpty()) {
                    return modificar(conexion, misReservas.get(aleatorio.nextInt(misReservas.size())), aleatorio);
                }
                break;
            case CANCELACION:
                if (!misReservas.isEmpty()) {
                    return cancelar(conexion, misReservas.remove(aleatorio.nextInt(misReservas.size())));
                }
                break;
            default:
                break;
        }
        return reservar(conexion, selector.siguiente(), elegirAsientos(aleatorio));
    }

    private int elegirAsientos(Random aleatorio) {
        return cfg.asientosMin + aleatorio.nextInt(cfg.asientosMax - cfg.asientosMin + 1);
    }

    /** Envía una consulta. La latencia cubre solo el envío y la respuesta; el JSON se valida después. */
    private RegistroTransaccion consultar(ConexionGt conexion) {
        String mensaje = cfg.usaIdSol()
                ? ProtocoloSrb.mensajeConsulta(ProtocoloSrb.nuevoIdSolicitud()) : ProtocoloSrb.CONSULTA;
        Instant marca = Instant.now();
        long inicio = System.nanoTime();
        EstadoTransaccion estado;
        long finRespuesta;
        try {
            String respuesta = conexion.enviar(mensaje);
            finRespuesta = System.nanoTime();
            ProtocoloSrb.parsearConsulta(respuesta); // valida el formato de la respuesta
            estado = EstadoTransaccion.OK;
        } catch (ErrorComunicacionException e) {
            finRespuesta = System.nanoTime();
            estado = EstadoTransaccion.desde(e.getTipo());
        }
        return registroDe(marca, TipoOperacion.CONSULTA, inicio, finRespuesta, estado, null, null, null, null);
    }

    private RegistroTransaccion reservar(ConexionGt conexion, SelectorOcurrencia.Ocurrencia destino, int cantidad) {
        String mensaje = cfg.usaIdSol()
                ? ProtocoloSrb.mensajeReserva(ProtocoloSrb.nuevoIdSolicitud(), destino.evento(), destino.numero(), cantidad)
                : ProtocoloSrb.mensajeReserva(destino.evento(), destino.numero(), cantidad);
        Instant marca = Instant.now();
        long inicio = System.nanoTime();
        EstadoTransaccion estado;
        long finRespuesta;
        Long idReserva = null;
        try {
            String respuesta = conexion.enviar(mensaje);
            finRespuesta = System.nanoTime();
            if (ResultadoReserva.desde(respuesta) == ResultadoReserva.EXITOSA) {
                estado = EstadoTransaccion.OK;
                long id = ProtocoloSrb.idReserva(respuesta);
                if (id > 0) {
                    idReserva = id;
                    misReservas.add(id);
                    registro.agregar(id, new RegistroReservas.Reserva(clienteId, destino.evento(),
                            destino.numero(), cantidad));
                }
            } else {
                estado = EstadoTransaccion.RECHAZADO;
            }
        } catch (ErrorComunicacionException e) {
            finRespuesta = System.nanoTime();
            estado = EstadoTransaccion.desde(e.getTipo());
        }
        return registroDe(marca, TipoOperacion.RESERVA, inicio, finRespuesta, estado,
                destino.evento(), destino.numero(), cantidad, idReserva);
    }

    /** Cambia la cantidad y, si el evento tiene dos fechas, con probabilidad 1/2 también la ocurrencia. */
    private RegistroTransaccion modificar(ConexionGt conexion, long idReserva, Random aleatorio) {
        RegistroReservas.Reserva r = registro.obtener(idReserva);
        int ocurrenciaNueva = r.ocurrencia;
        if (ocurrenciasPorEvento.getOrDefault(r.evento, 1) > 1 && aleatorio.nextBoolean()) {
            ocurrenciaNueva = r.ocurrencia == 1 ? 2 : 1;
        }
        int cantidadNueva = elegirAsientos(aleatorio);
        String mensaje = ProtocoloSrb.mensajeModificar(ProtocoloSrb.nuevoIdSolicitud(), idReserva,
                ocurrenciaNueva, cantidadNueva);
        Instant marca = Instant.now();
        long inicio = System.nanoTime();
        EstadoTransaccion estado;
        long finRespuesta;
        try {
            String respuesta = conexion.enviar(mensaje);
            finRespuesta = System.nanoTime();
            if (ResultadoReserva.desde(respuesta) == ResultadoReserva.EXITOSA) {
                estado = EstadoTransaccion.OK;
                r.ocurrencia = ocurrenciaNueva;
                r.cantidad = cantidadNueva;
            } else {
                estado = EstadoTransaccion.RECHAZADO; // la reserva queda como estaba
            }
        } catch (ErrorComunicacionException e) {
            finRespuesta = System.nanoTime();
            estado = EstadoTransaccion.desde(e.getTipo());
        }
        return registroDe(marca, TipoOperacion.MODIFICACION, inicio, finRespuesta, estado,
                r.evento, ocurrenciaNueva, cantidadNueva, idReserva);
    }

    /**
     * Envía una cancelación asíncrona. La latencia registrada es la de la respuesta ACEPTADA; la llegada
     * de la notificación la registra el hilo receptor como NOTIF_CANCELACION.
     */
    private RegistroTransaccion cancelar(ConexionGt conexion, long idReserva) {
        RegistroReservas.Reserva r = registro.obtener(idReserva);
        String mensaje = ProtocoloSrb.mensajeCancelar(ProtocoloSrb.nuevoIdSolicitud(), idReserva, cfg.ip, cfg.puertoNotif);
        long solicitudId = secuencia.incrementAndGet();
        Instant marca = Instant.now();
        long inicio = System.nanoTime();
        // Se registra antes de enviar: la notificación puede llegar antes de que vuelva ACEPTADA.
        r.estado = RegistroReservas.Estado.CANCELANDO;
        registro.registrarCancelacionPendiente(idReserva, new RegistroReservas.CancelacionPendiente(
                solicitudId, clienteId, perfil, marca, inicio, r.evento, r.ocurrencia, r.cantidad));
        EstadoTransaccion estado;
        long finRespuesta;
        try {
            String respuesta = conexion.enviar(mensaje);
            finRespuesta = System.nanoTime();
            if (ProtocoloSrb.RESPUESTA_ACEPTADA.equals(respuesta)) {
                estado = EstadoTransaccion.OK;
            } else {
                estado = EstadoTransaccion.RESPUESTA_INVALIDA;
                anularCancelacion(idReserva, r);
            }
        } catch (ErrorComunicacionException e) {
            finRespuesta = System.nanoTime();
            estado = EstadoTransaccion.desde(e.getTipo());
            if (e.getTipo() == ErrorComunicacionException.Tipo.NO_ENVIADO) {
                anularCancelacion(idReserva, r); // no salió: la reserva sigue activa
            }
        }
        return new RegistroTransaccion(solicitudId, marca, clienteId, perfil, TipoOperacion.CANCELACION,
                (finRespuesta - inicio) / 1_000_000.0, estado, r.evento, r.ocurrencia, r.cantidad, idReserva);
    }

    private void anularCancelacion(long idReserva, RegistroReservas.Reserva r) {
        if (registro.retirarCancelacionPendiente(idReserva) != null) {
            r.estado = RegistroReservas.Estado.ACTIVA;
            misReservas.add(idReserva);
        }
    }

    private RegistroTransaccion registroDe(Instant marca, TipoOperacion tipo, long inicio, long fin,
                                           EstadoTransaccion estado, String evento, Integer ocurrencia,
                                           Integer cantidad, Long idReserva) {
        return new RegistroTransaccion(secuencia.incrementAndGet(), marca, clienteId, perfil, tipo,
                (fin - inicio) / 1_000_000.0, estado, evento, ocurrencia, cantidad, idReserva);
    }

    // Espera en intervalos cortos para atender una detención; devuelve false si se pidió detener.
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
