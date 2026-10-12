package pruebas;

import cliente.ConexionGt;
import cliente.ErrorComunicacionException;
import cliente.EventoDto;
import cliente.ProtocoloSrb;

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
 * Coordina la ejecución de una prueba de carga:
 * <ol>
 *   <li>Consulta inicial: verifica el GT, obtiene las ocurrencias y la disponibilidad inicial.</li>
 *   <li>Crea un {@code ExecutorService} con un hilo por {@link ClienteSimulado}.</li>
 *   <li>Inicia todos los clientes simultáneamente cuando sus sockets están listos.</li>
 *   <li>Espera su finalización y cierra el ejecutor y el escritor CSV.</li>
 *   <li>Consulta final y verificación de consistencia de asientos.</li>
 *   <li>Cálculo de métricas a partir del CSV y escritura del resumen.</li>
 * </ol>
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
                                     long registrosEscritos, IOException errorEscritura,
                                     AnalizadorMetricas.Resultado metricas, double duracionRealSeg,
                                     boolean ejecutorTerminado, boolean escritorTerminado,
                                     int conexionesAbiertasAlFinal, Consistencia consistencia,
                                     boolean interrumpida, String textoResumen) {
    }

    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String ACUMULADO = "resumen-escenarios_excel.csv";

    private final PrintStream salida;

    public GeneradorCarga(PrintStream salida) {
        this.salida = salida;
    }

    /**
     * Ejecuta la prueba.
     *
     * @param detener indicador de detención anticipada (Ctrl+C), consultado periódicamente por los clientes
     * @throws IllegalStateException si el GT no responde a la consulta inicial
     */
    public ResultadoEjecucion ejecutar(ConfiguracionCarga cfg, AtomicBoolean detener)
            throws IOException, InterruptedException {
        cfg.validar();
        salida.println("==========================================================");
        salida.println("[Generador] " + cfg.descripcion().replace("\n", "\n[Generador] "));
        salida.println("==========================================================");

        // Conexión del hilo principal, usada solo para las consultas inicial y final.
        try (ConexionGt conexionControl = new ConexionGt(cfg.host, cfg.puerto, cfg.timeoutMs)) {

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
            EscritorCsv escritor = new EscritorCsv(csv, csvExcel);
            try {
                // Un hilo por cliente
                ExecutorService ejecutor = Executors.newFixedThreadPool(cfg.clientes, fabricaDeHilos());
                ClienteSimulado.Arranque arranque = new ClienteSimulado.Arranque(cfg.clientes);
                AtomicLong secuencia = new AtomicLong();
                List<Future<ClienteSimulado.Resumen>> futuros = new ArrayList<>();
                for (int i = 1; i <= cfg.clientes; i++) {
                    futuros.add(ejecutor.submit(new ClienteSimulado(i, cfg, destinos, escritor, arranque,
                            secuencia, conexionesAbiertas, detener)));
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
            } finally {
                escritor.close(); // escribe los registros pendientes y cierra los archivos
            }

            // Consulta final y verificación de consistencia
            Consistencia consistencia = verificarConsistencia(conexionControl, catalogoInicial, csv);

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
                    escritor.getEscritos(), escritor.getError(), metricas, duracionReal,
                    ejecutorTerminado, escritor.hiloTerminado(), conexionesAbiertas.get(),
                    consistencia, detener.get(), texto);
        }
    }

    private Map<String, EventoDto> consultarCatalogo(ConexionGt conexion, String momento) {
        try {
            return ProtocoloSrb.parsearConsulta(conexion.enviar(ProtocoloSrb.CONSULTA));
        } catch (ErrorComunicacionException e) {
            throw new IllegalStateException("La consulta " + momento + " al GT falló [" + e.getTipo() + "]: "
                    + e.getMessage() + " ¿Están en ejecución Persistencia, RMC y GT?", e);
        }
    }

    private Consistencia verificarConsistencia(ConexionGt conexion, Map<String, EventoDto> inicial, Path csv)
            throws IOException {
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
            if (r.tipo() != TipoOperacion.RESERVA) {
                continue;
            }
            if (r.estado() == EstadoTransaccion.OK) {
                reservadoOk.merge(r.evento() + ":" + r.ocurrencia(), (long) r.cantidad(), Long::sum);
            } else if (r.estado() == EstadoTransaccion.SIN_RESPUESTA) {
                inciertas++;
            }
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
                    discrepancias.add(String.format("%s: inicial %d, final %d (bajó %d) pero las reservas OK suman %d",
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
        sb.append(String.format(Locale.ROOT, "  %-5s %-14s %-12s %9s %8s %9s %9s%n",
                "Id", "Perfil", "Hilo", "Enviadas", "Por min", "Consultas", "Reservas"));
        for (ClienteSimulado.Resumen r : clientes) {
            long consultas = r.conteo().get(TipoOperacion.CONSULTA).values().stream().mapToLong(Long::longValue).sum();
            long reservas = r.conteo().get(TipoOperacion.RESERVA).values().stream().mapToLong(Long::longValue).sum();
            sb.append(String.format(Locale.ROOT, "  %-5s %-14s %-12s %9d %8.1f %9d %9d%n",
                    r.clienteId(), r.perfil(), r.hilo(), r.enviadas(),
                    r.enviadas() / (cfg.duracionSeg / 60.0), consultas, reservas));
        }
        for (String f : fallos) {
            sb.append("  AVISO: ").append(f).append('\n');
        }
        sb.append('\n');
        sb.append(AnalizadorMetricas.formatear(m));
        sb.append('\n');
        sb.append("Consistencia de asientos (disponibilidad inicial - final = reservas OK):\n");
        if (!c.verificada()) {
            sb.append("  No verificada: ").append(String.join("; ", c.discrepancias())).append('\n');
        } else {
            sb.append(String.format(Locale.ROOT,
                    "  %s - %d ocurrencias revisadas, asientos reservados OK: %d, asientos descontados en el servidor: %d%n",
                    c.correcta() ? "CORRECTA" : "CON DIFERENCIAS", c.ocurrenciasRevisadas(),
                    c.asientosReservadosOk(), c.asientosDescontados()));
            sb.append("  Disponibilidad negativa: ").append(c.hayNegativos() ? "SÍ" : "no").append('\n');
            if (c.reservasInciertas() > 0) {
                sb.append("  Reservas sin respuesta (pudieron aplicarse o no): ").append(c.reservasInciertas()).append('\n');
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

    /** Agrega las filas CONSULTA, RESERVA y TOTAL de la prueba al resumen acumulado. */
    private void agregarAlAcumulado(ConfiguracionCarga cfg, Path csv, AnalizadorMetricas.Resultado m,
                                    Consistencia c, boolean interrumpida) throws IOException {
        Path acumulado = cfg.carpetaSalida.resolve(ACUMULADO);
        boolean nuevo = !Files.exists(acumulado);
        try (BufferedWriter w = Files.newBufferedWriter(acumulado, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (nuevo) {
                w.write('﻿');
                w.write("fecha;escenario;clientes;solo_consulta;mixtos;duracion_s;rpm_cliente;pct_reserva;"
                        + "operacion;enviadas;respondidas;ok;rechazadas;errores_tecnicos;promedio_ms;"
                        + "desviacion_ms;p95_ms;tpm;ventana_s;consistencia;interrumpida;archivo_csv");
                w.newLine();
            }
            String fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            for (AnalizadorMetricas.Estadistica e : List.of(m.consulta(), m.reserva(), m.total())) {
                w.write(String.join(";", fecha, cfg.nombre, Integer.toString(cfg.clientes),
                        Integer.toString(cfg.soloConsulta), Integer.toString(cfg.clientesMixtos()),
                        Integer.toString(cfg.duracionSeg), Integer.toString(cfg.rpm), Integer.toString(cfg.pctReserva),
                        e.operacion(), Long.toString(e.enviadas()), Long.toString(e.respondidas()),
                        Long.toString(e.ok()), Long.toString(e.rechazadas()), Long.toString(e.erroresTecnicos()),
                        decimal(e.promedioMs()), decimal(e.desviacionMs()), decimal(e.p95Ms()), decimal(e.tpm()),
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
