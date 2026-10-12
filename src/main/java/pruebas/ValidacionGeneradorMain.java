package pruebas;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validación automática del generador de carga contra el backend en ejecución (unos 30 s).
 *
 * <p>Fase 1 (12 s, 3 clientes): conexión, concurrencia, sockets independientes, respuestas, frecuencia,
 * registro y formato del CSV, coherencia del resumen, liberación de recursos y consistencia de asientos.</p>
 *
 * <p>Fase 2 (15 s, 4 clientes): reservas concurrentes sobre una misma ocurrencia hasta agotarla, para
 * comprobar que la disponibilidad no queda negativa y que lo descontado coincide con lo reservado.</p>
 *
 * <p>Consume asientos, por lo que se debe reiniciar Persistencia antes de ejecutar los escenarios.</p>
 *
 * <pre>java -cp "target/classes;target/dependency/*" pruebas.ValidacionGeneradorMain [--gt=localhost:5555]</pre>
 */
public class ValidacionGeneradorMain {

    private final List<String> lineas = new ArrayList<>();
    private int pasan;
    private int fallan;

    public static void main(String[] args) throws Exception {
        String gt = "--gt=localhost:5555";
        String timeout = "--timeout=5000";
        for (String a : args) {
            if (a.startsWith("--gt=")) {
                gt = a;
            } else if (a.startsWith("--timeout=")) {
                timeout = a;
            }
        }
        System.exit(new ValidacionGeneradorMain().ejecutar(gt, timeout) ? 0 : 1);
    }

    boolean ejecutar(String gt, String timeout) throws Exception {
        Path carpeta = Paths.get("resultados", "validacion");

        // Fase 1: funcionamiento general
        ConfiguracionCarga f1 = ConfiguracionCarga.desdeArgumentos(new String[]{
                "--clientes=3", "--solo-consulta=1", "--duracion=12", "--rpm=30", "--pct-reserva=50",
                "--asientos=1-2", "--seleccion=aleatoria", gt, timeout, "--salida=" + carpeta});
        f1.nombre = "validacion";
        GeneradorCarga.ResultadoEjecucion r1;
        try {
            r1 = new GeneradorCarga(System.out).ejecutar(f1, new AtomicBoolean(false));
        } catch (IllegalStateException e) {
            verificar("1. El generador se conecta al GT", false, e.getMessage());
            imprimirInforme();
            return false;
        }
        AnalizadorMetricas.Resultado m = r1.metricas();

        verificar("1. El generador se conecta al GT",
                m.consulta().ok() > 0,
                "consulta inicial y " + m.consulta().ok() + " consultas OK");

        long maxPrimero = Long.MIN_VALUE;
        long minUltimo = Long.MAX_VALUE;
        boolean todosEnviaron = r1.clientes().size() == f1.clientes;
        for (ClienteSimulado.Resumen c : r1.clientes()) {
            todosEnviaron &= c.enviadas() > 0;
            maxPrimero = Math.max(maxPrimero, c.primerEnvioNanos());
            minUltimo = Math.min(minUltimo, c.ultimoEnvioNanos());
        }
        verificar("2. Los clientes envían de forma concurrente",
                todosEnviaron && maxPrimero < minUltimo,
                String.format(Locale.ROOT, "%d clientes activos a la vez durante %.1f s",
                        r1.clientes().size(), (minUltimo - maxPrimero) / 1e9));

        Set<Integer> conexiones = new HashSet<>();
        Set<String> hilos = new HashSet<>();
        for (ClienteSimulado.Resumen c : r1.clientes()) {
            conexiones.add(c.conexionId());
            hilos.add(c.hilo());
        }
        verificar("3. Cada cliente usa su propio socket ZeroMQ",
                conexiones.size() == f1.clientes && hilos.size() == f1.clientes,
                conexiones.size() + " conexiones distintas en " + hilos.size() + " hilos distintos " + hilos);

        verificar("4. Las consultas se procesan correctamente",
                m.consulta().enviadas() > 0 && m.consulta().ok() == m.consulta().enviadas(),
                m.consulta().ok() + " de " + m.consulta().enviadas() + " consultas con JSON válido");

        verificar("5. Las reservas reciben OK o RECHAZADO",
                m.reserva().enviadas() > 0 && m.reserva().respondidas() == m.reserva().enviadas(),
                m.reserva().ok() + " OK + " + m.reserva().rechazadas() + " RECHAZADO de "
                        + m.reserva().enviadas() + " reservas");

        double esperado = f1.duracionSeg * f1.rpm / 60.0;
        double tolerancia = Math.max(1, esperado * 0.15);
        boolean ritmoOk = true;
        StringBuilder detalleRitmo = new StringBuilder();
        for (ClienteSimulado.Resumen c : r1.clientes()) {
            ritmoOk &= Math.abs(c.enviadas() - esperado) <= tolerancia;
            detalleRitmo.append(c.clienteId()).append('=').append(c.enviadas()).append(' ');
        }
        verificar("6. Solicitudes acordes a la frecuencia configurada", ritmoOk,
                String.format(Locale.ROOT, "esperado %.0f±%.0f por cliente: %s", esperado, tolerancia,
                        detalleRitmo.toString().trim()));

        long enviadasClientes = r1.clientes().stream().mapToLong(ClienteSimulado.Resumen::enviadas).sum();
        verificar("7. Cada transacción queda registrada en el CSV",
                enviadasClientes == r1.registrosEscritos() && enviadasClientes == m.registrosTotales()
                        && r1.errorEscritura() == null,
                enviadasClientes + " enviadas = " + r1.registrosEscritos() + " escritas = "
                        + m.registrosTotales() + " leídas del CSV");

        String errorFormato = revisarFormatoCsv(r1, f1);
        verificar("8. Columnas y datos del CSV correctos", errorFormato == null,
                errorFormato == null ? "encabezado, 10 columnas, ids únicos, valores válidos, CSV Excel con igual número de filas"
                        : errorFormato);

        verificar("9. El resumen estadístico coincide con los registros",
                coincidenConteos(r1, m),
                "conteos por operación y estado de los hilos = conteos calculados desde el CSV");

        verificar("10. El generador finaliza y libera recursos",
                r1.ejecutorTerminado() && r1.escritorTerminado() && r1.conexionesAbiertasAlFinal() == 0
                        && r1.fallosClientes().isEmpty(),
                "ejecutor terminado=" + r1.ejecutorTerminado() + ", escritor terminado=" + r1.escritorTerminado()
                        + ", sockets abiertos=" + r1.conexionesAbiertasAlFinal());

        GeneradorCarga.Consistencia c1 = r1.consistencia();
        verificar("11. Disponibilidad consistente tras la carga",
                c1.verificada() && c1.correcta() && !c1.hayNegativos(),
                c1.asientosReservadosOk() + " asientos reservados OK = " + c1.asientosDescontados()
                        + " descontados en el servidor");

        // Fase 2: contención sobre una sola ocurrencia
        ConfiguracionCarga f2 = ConfiguracionCarga.desdeArgumentos(new String[]{
                "--clientes=4", "--solo-consulta=0", "--duracion=15", "--rpm=60", "--pct-reserva=100",
                "--asientos=3", "--seleccion=fija", "--evento=E50", "--ocurrencia=1", gt, timeout,
                "--salida=" + carpeta});
        f2.nombre = "contencion";
        GeneradorCarga.ResultadoEjecucion r2 = new GeneradorCarga(System.out).ejecutar(f2, new AtomicBoolean(false));
        GeneradorCarga.Consistencia c2 = r2.consistencia();
        AnalizadorMetricas.Estadistica res2 = r2.metricas().reserva();
        verificar("12. Concurrencia sobre una misma ocurrencia sin sobreventa",
                c2.verificada() && c2.correcta() && !c2.hayNegativos() && res2.rechazadas() > 0
                        && res2.erroresTecnicos() == 0,
                res2.ok() + " reservas OK (" + c2.asientosReservadosOk() + " asientos) y " + res2.rechazadas()
                        + " rechazadas por agotamiento; descontados en E50:1 = " + c2.asientosDescontados()
                        + "; negativos: " + (c2.hayNegativos() ? "sí" : "no"));

        imprimirInforme();
        System.out.println("Archivos de la validación en: " + carpeta.toAbsolutePath());
        System.out.println("IMPORTANTE: reinicie Persistencia antes de ejecutar los escenarios A, B y C.");
        return fallan == 0;
    }

    /** Revisa la estructura y los valores de cada fila del CSV. */
    private static String revisarFormatoCsv(GeneradorCarga.ResultadoEjecucion r, ConfiguracionCarga cfg)
            throws IOException {
        List<String> filas = Files.readAllLines(r.csv(), StandardCharsets.UTF_8);
        if (filas.isEmpty() || !filas.get(0).equals(RegistroTransaccion.ENCABEZADO)) {
            return "encabezado incorrecto";
        }
        if (!filas.get(0).startsWith("timestamp,cliente_id,tipo_operacion,latencia_ms,estado")) {
            return "las columnas obligatorias no están al inicio";
        }
        Set<Long> ids = new HashSet<>();
        for (int i = 1; i < filas.size(); i++) {
            RegistroTransaccion t;
            try {
                t = RegistroTransaccion.desdeCsv(filas.get(i));
            } catch (RuntimeException e) {
                return "línea " + (i + 1) + " no se puede interpretar: " + e.getMessage();
            }
            if (!ids.add(t.solicitudId())) {
                return "solicitud_id repetido: " + t.solicitudId();
            }
            int numCliente = Integer.parseInt(t.clienteId().substring(1));
            if (numCliente < 1 || numCliente > cfg.clientes || t.perfil() != cfg.perfilDe(numCliente)) {
                return "cliente o perfil inválido en línea " + (i + 1);
            }
            if (t.latenciaMs() < 0) {
                return "latencia negativa en línea " + (i + 1);
            }
            boolean esReserva = t.tipo() == TipoOperacion.RESERVA;
            if (esReserva != (t.evento() != null && t.ocurrencia() != null && t.cantidad() != null)) {
                return "evento/ocurrencia/cantidad incoherentes con la operación en línea " + (i + 1);
            }
            if (esReserva && (t.cantidad() < cfg.asientosMin || t.cantidad() > cfg.asientosMax)) {
                return "cantidad fuera de rango en línea " + (i + 1);
            }
            if (t.tipo() == TipoOperacion.CONSULTA && t.estado() == EstadoTransaccion.RECHAZADO) {
                return "una consulta no puede ser RECHAZADO (línea " + (i + 1) + ")";
            }
        }
        long filasExcel;
        try (BufferedReader lector = Files.newBufferedReader(r.csvExcel(), StandardCharsets.UTF_8)) {
            filasExcel = lector.lines().count();
        }
        if (filasExcel != filas.size()) {
            return "el CSV para Excel tiene " + filasExcel + " filas y el estándar " + filas.size();
        }
        return null;
    }

    /** Compara los conteos de los hilos con los calculados desde el CSV. */
    private static boolean coincidenConteos(GeneradorCarga.ResultadoEjecucion r, AnalizadorMetricas.Resultado m) {
        Map<TipoOperacion, Map<EstadoTransaccion, Long>> suma = new EnumMap<>(TipoOperacion.class);
        for (TipoOperacion t : TipoOperacion.values()) {
            suma.put(t, new EnumMap<>(EstadoTransaccion.class));
        }
        for (ClienteSimulado.Resumen c : r.clientes()) {
            c.conteo().forEach((tipo, porEstado) ->
                    porEstado.forEach((estado, n) -> suma.get(tipo).merge(estado, n, Long::sum)));
        }
        return igual(suma.get(TipoOperacion.CONSULTA), m.consulta())
                && igual(suma.get(TipoOperacion.RESERVA), m.reserva());
    }

    private static boolean igual(Map<EstadoTransaccion, Long> c, AnalizadorMetricas.Estadistica e) {
        return c.getOrDefault(EstadoTransaccion.OK, 0L) == e.ok()
                && c.getOrDefault(EstadoTransaccion.RECHAZADO, 0L) == e.rechazadas()
                && c.getOrDefault(EstadoTransaccion.NO_ENVIADO, 0L) == e.noEnviadas()
                && c.getOrDefault(EstadoTransaccion.SIN_RESPUESTA, 0L) == e.sinRespuesta()
                && c.getOrDefault(EstadoTransaccion.RESPUESTA_INVALIDA, 0L) == e.respuestasInvalidas()
                && c.values().stream().mapToLong(Long::longValue).sum() == e.enviadas();
    }

    private void verificar(String nombre, boolean ok, String detalle) {
        if (ok) {
            pasan++;
        } else {
            fallan++;
        }
        lineas.add(String.format("[%s] %s%n         %s", ok ? "PASA " : "FALLA", nombre, detalle));
    }

    private void imprimirInforme() {
        System.out.println();
        System.out.println("==========================================================");
        System.out.println("VALIDACIÓN DEL GENERADOR DE CARGA");
        System.out.println("==========================================================");
        lineas.forEach(System.out::println);
        System.out.println("----------------------------------------------------------");
        System.out.println("Resultado: " + pasan + " PASA, " + fallan + " FALLA");
    }
}
