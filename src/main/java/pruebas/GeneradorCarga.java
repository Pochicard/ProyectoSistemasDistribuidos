package pruebas;

import cliente.ConexionGt;
import cliente.ErrorComunicacionException;
import cliente.EventoDto;
import cliente.ProtocoloSrb;
import cliente.ReceptorNotificaciones;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordina una prueba: consulta inicial, un hilo por cliente con arranque simultáneo, espera de las
 * notificaciones pendientes, consulta final con verificación de consistencia y métricas desde el CSV.
 */
public final class GeneradorCarga {

    /** Verificación de que la disponibilidad descontada coincide con las reservas aceptadas. */
    public record Consistencia(boolean verificada, boolean correcta, int ocurrenciasRevisadas,
                               long asientosReservadosOk, long asientosDescontados,
                               long reservasInciertas, boolean hayNegativos, List<String> discrepancias) {
    }

    /** Resultado de una ejecución, usado por el programa principal y por la validación. */
    public record ResultadoEjecucion(ConfiguracionCarga cfg, Path csv, Path csvExcel, Path resumen,
                                     List<ClienteSimulado.Resumen> clientes, List<String> fallosClientes,
                                     long registrosEscritos, long notificacionesRegistradas,
                                     IOException errorEscritura,
                                     AnalizadorMetricas.Resultado metricas, double duracionRealSeg,
                                     boolean ejecutorTerminado, boolean escritorTerminado,
                                     int conexionesAbiertasAlFinal, Consistencia consistencia,
                                     boolean interrumpida, String textoResumen) {
    }

    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Resumen acumulado con columnas de la segunda entrega (percentiles 99/99,9 y disponibilidad). */
    static final String ACUMULADO = "resumen-escenarios-v2_excel.csv";

    private final PrintStream salida;

    public GeneradorCarga(PrintStream salida) {
        this.salida = salida;
    }

    // Ejecuta la prueba. "detener" se activa con Ctrl+C y los clientes lo revisan periódicamente.
    public ResultadoEjecucion ejecutar(ConfiguracionCarga cfg, AtomicBoolean detener)
            throws IOException, InterruptedException {
        cfg.validar();
        salida.println("==========================================================");
        salida.println("[Generador] " + cfg.descripcion().replace("\n", "\n[Generador] "));
        salida.println("==========================================================");

        // Conexión del hilo principal, usada solo para las consultas inicial y final.
        try (ConexionGt conexionControl = new ConexionGt(cfg.gts, cfg.timeoutMs)) {

            // Consulta inicial
            Map<String, EventoDto> catalogoInicial = consultarCatalogo(conexionControl, "inicial");
            List<SelectorOcurrencia.Ocurrencia> todas = SelectorOcurrencia.desdeCatalogo(catalogoInicial);
            List<SelectorOcurrencia.Ocurrencia> destinos = todas;
            if (cfg.seleccion == SelectorOcurrencia.Estrategia.FIJA) {
                SelectorOcurrencia.Ocurrencia fija = new SelectorOcurrencia.Ocurrencia(cfg.eventoFijo, cfg.ocurrenciaFija);
                if (!todas.contains(fija)) {
                    throw new IllegalArgumentException("La ocurrencia fija " + fija + " no existe en el catálogo");
                }
                destinos = List.of(fija);
            }
            salida.printf(Locale.ROOT, "[Generador] Catálogo inicial: %d eventos, %d ocurrencias, %d asientos disponibles%n",
                    catalogoInicial.size(), todas.size(), totalAsientos(catalogoInicial));
            Map<String, Integer> ocurrenciasPorEvento = new HashMap<>();
            catalogoInicial.values().forEach(e -> ocurrenciasPorEvento.put(e.getId(), e.getOcurrencias()));

            // Archivos de salida con nombre único para no sobrescribir pruebas anteriores
            Files.createDirectories(cfg.carpetaSalida);
            String base = nombreUnico(cfg.carpetaSalida, cfg.prefijoArchivo() + "_" + LocalDateTime.now().format(SELLO));
            Path csv = cfg.carpetaSalida.resolve(base + ".csv");
            Path csvExcel = cfg.carpetaSalida.resolve(base + "_excel.csv");
            Path resumen = cfg.carpetaSalida.resolve(base + "_resumen.txt");

            List<ClienteSimulado.Resumen> resumenes = new ArrayList<>();
            List<String> fallos = new ArrayList<>();
            AtomicInteger conexionesAbiertas = new AtomicInteger();
            boolean ejecutorTerminado;
            double duracionReal;
            RegistroReservas registro = new RegistroReservas();
            AtomicLong notificaciones = new AtomicLong();
            EscritorCsv escritor = new EscritorCsv(csv, csvExcel);
            ReceptorNotificaciones receptor = null;
            try {
                if (cfg.pctCancelacion > 0) {
                    // Un único receptor PULL para todo el generador; cada notificación trae su idReserva.
                    receptor = new ReceptorNotificaciones(cfg.puertoNotif, (n, texto) -> {
                        if (n != null) {
                            registrarNotificacion(registro, escritor, notificaciones, n.idReserva(),
                                    n.cancelada() ? EstadoTransaccion.OK : EstadoTransaccion.RECHAZADO);
                        }
                    });
                }
                // Un hilo por cliente
                ExecutorService ejecutor = Executors.newFixedThreadPool(cfg.clientes, fabricaDeHilos());
                ClienteSimulado.Arranque arranque = new ClienteSimulado.Arranque(cfg.clientes);
                AtomicLong secuencia = new AtomicLong();
                List<Future<ClienteSimulado.Resumen>> futuros = new ArrayList<>();
                for (int i = 1; i <= cfg.clientes; i++) {
                    futuros.add(ejecutor.submit(new ClienteSimulado(i, cfg, destinos, ocurrenciasPorEvento,
                            escritor, registro, arranque, secuencia, conexionesAbiertas, detener)));
                }

                // Arranque simultáneo una vez que todos los clientes tienen su socket
                if (!arranque.listos.await(10, TimeUnit.SECONDS)) {
                    fallos.add("No todos los clientes crearon su conexión en 10 s");
                    detener.set(true);
                }
                arranque.t0Nanos = System.nanoTime() + 100_000_000L;
                arranque.finNanos = arranque.t0Nanos + cfg.duracionSeg * 1_000_000_000L;
                arranque.inicio.countDown();
                salida.printf("[Generador] %d clientes en marcha (%d hilos, un socket REQ por hilo)...%n",
                        cfg.clientes, cfg.clientes);

                // Progreso cada 10 s mientras se ejecutan los clientes
                ejecutor.shutdown(); // no admite nuevas tareas; las actuales continúan
                while (!ejecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    long transcurrido = (System.nanoTime() - arranque.t0Nanos) / 1_000_000_000L;
                    salida.printf("[Generador]   %3d s / %d s  -  %d solicitudes registradas%n",
                            Math.max(0, transcurrido), cfg.duracionSeg, secuencia.get());
                    if (transcurrido > cfg.duracionSeg + 30L + cfg.timeoutMs / 1000L) {
                        salida.println("[Generador] Los clientes no terminan; se interrumpen.");
                        ejecutor.shutdownNow();
                        ejecutor.awaitTermination(5, TimeUnit.SECONDS);
                        break;
                    }
                }
                duracionReal = (System.nanoTime() - arranque.t0Nanos) / 1e9;
                ejecutorTerminado = ejecutor.isTerminated();

                for (Future<ClienteSimulado.Resumen> f : futuros) {
                    try {
                        resumenes.add(f.get());
                    } catch (ExecutionException e) {
                        fallos.add("Un cliente terminó con error: " + e.getCause());
                    } catch (java.util.concurrent.CancellationException e) {
                        fallos.add("Un cliente fue cancelado");
                    }
                }
                esperarNotificaciones(cfg, registro, escritor, notificaciones);
            } finally {
                if (receptor != null) {
                    receptor.close();
                }
                escritor.close(); // escribe los registros pendientes y cierra los archivos
            }

            // Consulta final y verificación de consistencia
            Consistencia consistencia = verificarConsistencia(cfg, conexionControl, catalogoInicial, csv, registro);

            // Métricas calculadas desde el CSV
            AnalizadorMetricas.Resultado metricas =
                    AnalizadorMetricas.analizar(AnalizadorMetricas.leerCsv(csv), cfg.descartarSeg,
                            detener.get() ? duracionReal : cfg.duracionSeg);

            String texto = construirResumen(cfg, csv, csvExcel, resumenes, fallos, metricas, duracionReal,
                    consistencia, detener.get(), escritor);
            Files.writeString(resumen, texto, StandardCharsets.UTF_8);
            agregarAlAcumulado(cfg, csv, metricas, consistencia, detener.get());
            salida.println();
            salida.print(texto);

            return new ResultadoEjecucion(cfg, csv, csvExcel, resumen, resumenes, fallos,
                    escritor.getEscritos(), notificaciones.get(), escritor.getError(), metricas, duracionReal,
                    ejecutorTerminado, escritor.hiloTerminado(), conexionesAbiertas.get(),
                    consistencia, detener.get(), texto);
        }
    }

    /** Registra la llegada de una notificación como fila NOTIF_CANCELACION y actualiza la reserva. */
    private static void registrarNotificacion(RegistroReservas registro, EscritorCsv escritor, AtomicLong contador,
                                              long idReserva, EstadoTransaccion estado) {
        RegistroReservas.CancelacionPendiente p = registro.retirarCancelacionPendiente(idReserva);
        if (p == null) {
            return; // notificación de una reserva que no es de esta prueba
        }
        RegistroReservas.Reserva r = registro.obtener(idReserva);
        if (r != null && estado != EstadoTransaccion.SIN_RESPUESTA) {
            r.estado = estado == EstadoTransaccion.OK ? RegistroReservas.Estado.CANCELADA : RegistroReservas.Estado.ACTIVA;
        }
        escritor.registrar(new RegistroTransaccion(p.solicitudId(), p.marca(), p.clienteId(), p.perfil(),
                TipoOperacion.NOTIF_CANCELACION, (System.nanoTime() - p.envioNanos()) / 1_000_000.0, estado,
                p.evento(), p.ocurrencia(), p.cantidad(), idReserva));
        contador.incrementAndGet();
    }

    /**
     * Espera las notificaciones de cancelación pendientes; las que no llegan a tiempo se registran como
     * SIN_RESPUESTA (la reserva queda en estado incierto).
     */
    private void esperarNotificaciones(ConfiguracionCarga cfg, RegistroReservas registro, EscritorCsv escritor,
                                       AtomicLong contador) throws InterruptedException {
        long limite = System.nanoTime() + cfg.esperaNotificacionesSeg * 1_000_000_000L;
        while (!registro.pendientes().isEmpty() && System.nanoTime() < limite) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        if (!registro.pendientes().isEmpty()) {
            salida.println("[Generador] " + registro.pendientes().size()
                    + " notificación(es) de cancelación no llegaron a tiempo.");
            for (Long id : new ArrayList<>(registro.pendientes().keySet())) {
                registrarNotificacion(registro, escritor, contador, id, EstadoTransaccion.SIN_RESPUESTA);
            }
        }
    }

    private Map<String, EventoDto> consultarCatalogo(ConexionGt conexion, String momento) {
        try {
            // Formato simple, aceptado por la arquitectura base y la escalable. La consulta de control sí
            // reintenta (pasa al otro GT si hay dos) porque es idempotente.
            return ProtocoloSrb.parsearConsulta(conexion.enviarConReintentos(ProtocoloSrb.CONSULTA,
                    conexion.getEndpoints().size() + 1, null));
        } catch (ErrorComunicacionException e) {
            throw new IllegalStateException("La consulta " + momento + " al GT falló [" + e.getTipo() + "]: "
                    + e.getMessage() + " ¿Están en ejecución Persistencia, RMC y GT?", e);
        }
    }

    /**
     * Compara, por ocurrencia, los asientos que bajó la disponibilidad con los que deberían estar ocupados.
     * Con protocolo v2 lo esperado sale del registro de reservas (incluye modificaciones y cancelaciones);
     * con v1, de la suma de reservas OK del CSV.
     */
    private Consistencia verificarConsistencia(ConfiguracionCarga cfg, ConexionGt conexion,
                                               Map<String, EventoDto> inicial, Path csv,
                                               RegistroReservas registro) throws IOException {
        Map<String, EventoDto> finalCat;
        try {
            finalCat = consultarCatalogo(conexion, "final");
        } catch (IllegalStateException e) {
            salida.println("[Generador] " + e.getMessage());
            return new Consistencia(false, false, 0, 0, 0, 0, false, List.of("No se pudo hacer la consulta final"));
        }
        Map<String, Long> reservadoOk = new HashMap<>();
        long inciertas = 0;
        for (RegistroTransaccion r : AnalizadorMetricas.leerCsv(csv)) {
            if (r.tipo() == TipoOperacion.CONSULTA) {
                continue;
            }
            if (r.estado() == EstadoTransaccion.SIN_RESPUESTA) {
                inciertas++; // pudo aplicarse o no
            }
            if (!cfg.usaIdSol() && r.tipo() == TipoOperacion.RESERVA && r.estado() == EstadoTransaccion.OK) {
                reservadoOk.merge(r.evento() + ":" + r.ocurrencia(), (long) r.cantidad(), Long::sum);
            }
        }
        if (cfg.usaIdSol()) {
            reservadoOk = registro.asientosOcupadosPorOcurrencia();
        }
        List<String> discrepancias = new ArrayList<>();
        boolean negativos = false;
        long totalOk = 0;
        long totalDescontado = 0;
        int revisadas = 0;
        for (EventoDto e : inicial.values()) {
            EventoDto f = finalCat.get(e.getId());
            for (int o = 1; o <= e.getOcurrencias(); o++) {
                revisadas++;
                String clave = e.getId() + ":" + o;
                if (f == null) {
                    discrepancias.add(clave + " no aparece en la consulta final");
                    continue;
                }
                long descontado = e.getDisponibles(o) - f.getDisponibles(o);
                long esperado = reservadoOk.getOrDefault(clave, 0L);
                totalOk += esperado;
                totalDescontado += descontado;
                if (f.getDisponibles(o) < 0) {
                    negativos = true;
                    discrepancias.add(clave + " quedó con disponibilidad negativa: " + f.getDisponibles(o));
                }
                if (descontado != esperado) {
                    discrepancias.add(String.format("%s: inicial %d, final %d (bajó %d) pero las reservas activas suman %d",
                            clave, e.getDisponibles(o), f.getDisponibles(o), descontado, esperado));
                }
            }
        }
        return new Consistencia(true, discrepancias.isEmpty(), revisadas, totalOk, totalDescontado,
                inciertas, negativos, discrepancias);
    }

    private String construirResumen(ConfiguracionCarga cfg, Path csv, Path csvExcel,
                                    List<ClienteSimulado.Resumen> clientes, List<String> fallos,
                                    AnalizadorMetricas.Resultado m, double duracionReal,
                                    Consistencia c, boolean interrumpida, EscritorCsv escritor) {
        StringBuilder sb = new StringBuilder();
        sb.append("==========================================================\n");
        sb.append("RESUMEN DE LA PRUEBA - ").append(cfg.prefijoArchivo())
                .append(interrumpida ? "  (INTERRUMPIDA)" : "").append('\n');
        sb.append("==========================================================\n");
        sb.append(cfg.descripcion()).append('\n');
        sb.append(String.format(Locale.ROOT, "Duración configurada: %d s | duración real: %.1f s%n",
                cfg.duracionSeg, duracionReal));
        sb.append("CSV: ").append(csv.toAbsolutePath()).append('\n');
        sb.append("CSV para Excel: ").append(csvExcel.toAbsolutePath()).append('\n');
        sb.append("Registros escritos: ").append(escritor.getEscritos());
        if (escritor.getError() != null) {
            sb.append("  ERROR DE ESCRITURA: ").append(escritor.getError().getMessage());
        }
        sb.append("\n\n");

        double esperadasPorCliente = cfg.duracionSeg * cfg.rpm / 60.0;
        sb.append(String.format(Locale.ROOT, "Clientes (esperado ~%.0f solicitudes c/u = %d por minuto):%n",
                esperadasPorCliente, cfg.rpm));
        sb.append(String.format(Locale.ROOT, "  %-5s %-14s %-10s %8s %7s %9s %8s %8s %8s%n",
                "Id", "Perfil", "Hilo", "Enviadas", "Por min", "Consultas", "Reservas", "Modific.", "Cancel."));
        for (ClienteSimulado.Resumen r : clientes) {
            sb.append(String.format(Locale.ROOT, "  %-5s %-14s %-10s %8d %7.1f %9d %8d %8d %8d%n",
                    r.clienteId(), r.perfil(), r.hilo(), r.enviadas(), r.enviadas() / (cfg.duracionSeg / 60.0),
                    total(r, TipoOperacion.CONSULTA), total(r, TipoOperacion.RESERVA),
                    total(r, TipoOperacion.MODIFICACION), total(r, TipoOperacion.CANCELACION)));
        }
        for (String f : fallos) {
            sb.append("  AVISO: ").append(f).append('\n');
        }
        sb.append('\n');
        sb.append(AnalizadorMetricas.formatear(m));
        sb.append('\n');
        sb.append("Consistencia de asientos (disponibilidad inicial - final = asientos de las reservas activas):\n");
        if (!c.verificada()) {
            sb.append("  No verificada: ").append(String.join("; ", c.discrepancias())).append('\n');
        } else {
            sb.append(String.format(Locale.ROOT,
                    "  %s - %d ocurrencias revisadas, asientos en reservas activas: %d, descontados en el servidor: %d%n",
                    c.correcta() ? "CORRECTA" : "CON DIFERENCIAS", c.ocurrenciasRevisadas(),
                    c.asientosReservadosOk(), c.asientosDescontados()));
            sb.append("  Disponibilidad negativa: ").append(c.hayNegativos() ? "SÍ" : "no").append('\n');
            if (c.reservasInciertas() > 0) {
                sb.append("  Operaciones sin respuesta (pudieron aplicarse o no): ").append(c.reservasInciertas()).append('\n');
            }
            for (String d : c.discrepancias()) {
                sb.append("  - ").append(d).append('\n');
            }
            if (!c.correcta()) {
                sb.append("  (Válido solo si ningún otro cliente usó el sistema durante la prueba.)\n");
            }
        }
        return sb.toString();
    }

    private static long total(ClienteSimulado.Resumen r, TipoOperacion tipo) {
        return r.conteo().get(tipo).values().stream().mapToLong(Long::longValue).sum();
    }

    /** Agrega una fila por tipo de operación, más el total, al resumen acumulado de todas las pruebas. */
    private void agregarAlAcumulado(ConfiguracionCarga cfg, Path csv, AnalizadorMetricas.Resultado m,
                                    Consistencia c, boolean interrumpida) throws IOException {
        Path acumulado = cfg.carpetaSalida.resolve(ACUMULADO);
        boolean nuevo = !Files.exists(acumulado);
        try (BufferedWriter w = Files.newBufferedWriter(acumulado, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (nuevo) {
                w.write('\uFEFF'); // BOM para que Excel detecte UTF-8
                w.write("fecha;escenario;gt;protocolo;clientes;solo_consulta;mixtos;duracion_s;rpm_cliente;"
                        + "pct_reserva;pct_modificacion;pct_cancelacion;operacion;enviadas;respondidas;ok;rechazadas;"
                        + "errores_tecnicos;disponibilidad_pct;promedio_ms;desviacion_ms;p95_ms;p99_ms;p999_ms;tpm;"
                        + "ventana_s;consistencia;interrumpida;archivo_csv");
                w.newLine();
            }
            String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            List<AnalizadorMetricas.Estadistica> filas = new ArrayList<>(m.porTipo().values());
            filas.add(m.total());
            for (AnalizadorMetricas.Estadistica e : filas) {
                w.write(String.join(";", fecha, cfg.nombre, String.join(" ", cfg.gts), cfg.protocolo,
                        Integer.toString(cfg.clientes), Integer.toString(cfg.soloConsulta),
                        Integer.toString(cfg.clientesMixtos()), Integer.toString(cfg.duracionSeg),
                        Integer.toString(cfg.rpm), Integer.toString(cfg.pctReserva),
                        Integer.toString(cfg.pctModificacion), Integer.toString(cfg.pctCancelacion),
                        e.operacion(), Long.toString(e.enviadas()), Long.toString(e.respondidas()),
                        Long.toString(e.ok()), Long.toString(e.rechazadas()), Long.toString(e.erroresTecnicos()),
                        decimal(e.disponibilidadPct()), decimal(e.promedioMs()), decimal(e.desviacionMs()),
                        decimal(e.p95Ms()), decimal(e.p99Ms()), decimal(e.p999Ms()), decimal(e.tpm()),
                        decimal(m.ventanaSeg()),
                        !c.verificada() ? "NO_VERIFICADA" : (c.correcta() ? "CORRECTA" : "CON_DIFERENCIAS"),
                        interrumpida ? "SI" : "NO", csv.getFileName().toString()));
                w.newLine();
            }
        }
    }

    /** Formato con coma decimal para Excel en español. */
    private static String decimal(double valor) {
        return String.format(Locale.ROOT, "%.3f", valor).replace('.', ',');
    }

    private static long totalAsientos(Map<String, EventoDto> catalogo) {
        long total = 0;
        for (EventoDto e : catalogo.values()) {
            for (int o = 1; o <= e.getOcurrencias(); o++) {
                total += e.getDisponibles(o);
            }
        }
        return total;
    }

    private static String nombreUnico(Path carpeta, String base) {
        String nombre = base;
        int n = 2;
        while (Files.exists(carpeta.resolve(nombre + ".csv"))) {
            nombre = base + "-" + n++;
        }
        return nombre;
    }

    /** Fábrica de hilos con nombres "cliente-N" para facilitar el seguimiento. */
    private static ThreadFactory fabricaDeHilos() {
        AtomicInteger contador = new AtomicInteger();
        return tarea -> new Thread(tarea, "cliente-" + contador.incrementAndGet());
    }
}
