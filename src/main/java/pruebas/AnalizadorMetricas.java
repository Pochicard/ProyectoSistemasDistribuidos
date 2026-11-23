package pruebas;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Métricas de rendimiento calculadas a partir del CSV, por tipo de operación y en total.
 *
 * <p>Respondidas = OK + RECHAZADO; errores técnicos = NO_ENVIADO + SIN_RESPUESTA + RESPUESTA_INVALIDA.
 * Tiempos (promedio, desviación muestral, percentiles 95/99/99,9) y TPM solo sobre las respondidas;
 * disponibilidad = respondidas / enviadas. NOTIF_CANCELACION se informa aparte y no suma al total.</p>
 *
 * <p>Uso: {@code pruebas.AnalizadorMetricas <archivo.csv> --duracion=150 [--descartar=10]}</p>
 */
public final class AnalizadorMetricas {

    /** Métricas de un tipo de operación o del total. */
    public record Estadistica(String operacion, long enviadas, long respondidas, long ok, long rechazadas,
                              long noEnviadas, long sinRespuesta, long respuestasInvalidas,
                              double promedioMs, double desviacionMs, double minimoMs, double maximoMs,
                              double p95Ms, double p99Ms, double p999Ms, double tpm) {

        public long erroresTecnicos() {
            return noEnviadas + sinRespuesta + respuestasInvalidas;
        }

        /** Porcentaje de solicitudes que obtuvieron respuesta válida. */
        public double disponibilidadPct() {
            return enviadas == 0 ? 0 : 100.0 * respondidas / enviadas;
        }
    }

    /** Resultado completo del análisis de un CSV. */
    public record Resultado(Map<TipoOperacion, Estadistica> porTipo, Estadistica total,
                            Instant inicioVentana, Instant finVentana, double ventanaSeg,
                            int registrosTotales, int registrosDescartados) {

        /** Métricas de un tipo; vacías si ese tipo no aparece en el CSV. */
        public Estadistica de(TipoOperacion tipo) {
            Estadistica e = porTipo.get(tipo);
            return e != null ? e : calcular(tipo.name(), List.of(), 0);
        }

        public Estadistica consulta() {
            return de(TipoOperacion.CONSULTA);
        }

        public Estadistica reserva() {
            return de(TipoOperacion.RESERVA);
        }
    }

    private AnalizadorMetricas() {
    }

    /** Lee las filas de un CSV estándar generado por {@link EscritorCsv} (también los de la primera entrega). */
    public static List<RegistroTransaccion> leerCsv(Path archivo) throws IOException {
        List<RegistroTransaccion> registros = new ArrayList<>();
        try (BufferedReader lector = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            String encabezado = lector.readLine();
            if (!RegistroTransaccion.ENCABEZADO.equals(encabezado)
                    && !RegistroTransaccion.ENCABEZADO_V1.equals(encabezado)) {
                throw new IOException("Encabezado inesperado en " + archivo + ": " + encabezado);
            }
            String linea;
            int numero = 1;
            while ((linea = lector.readLine()) != null) {
                numero++;
                if (linea.isBlank()) {
                    continue;
                }
                try {
                    registros.add(RegistroTransaccion.desdeCsv(linea));
                } catch (RuntimeException e) {
                    throw new IOException("Línea " + numero + " inválida: " + e.getMessage(), e);
                }
            }
        }
        return registros;
    }

    // descartarSeg: segundos iniciales de calentamiento que se excluyen.
    // duracionSeg: base para el TPM; si es <= 0 se deduce de los timestamps.
    public static Resultado analizar(List<RegistroTransaccion> todos, int descartarSeg, double duracionSeg) {
        if (todos.isEmpty()) {
            return new Resultado(new EnumMap<>(TipoOperacion.class), calcular("TOTAL", List.of(), 0),
                    null, null, 0, 0, 0);
        }
        Instant primero = todos.get(0).timestamp();
        Instant ultimo = todos.get(0).fin();
        for (RegistroTransaccion r : todos) {
            if (r.timestamp().isBefore(primero)) {
                primero = r.timestamp();
            }
            if (r.fin().isAfter(ultimo)) {
                ultimo = r.fin();
            }
        }
        Instant corte = primero.plusSeconds(descartarSeg);
        Map<TipoOperacion, List<RegistroTransaccion>> grupos = new EnumMap<>(TipoOperacion.class);
        List<RegistroTransaccion> solicitudes = new ArrayList<>();
        int medidos = 0;
        for (RegistroTransaccion r : todos) {
            if (r.timestamp().isBefore(corte)) {
                continue;
            }
            medidos++;
            grupos.computeIfAbsent(r.tipo(), t -> new ArrayList<>()).add(r);
            if (r.tipo() != TipoOperacion.NOTIF_CANCELACION) {
                solicitudes.add(r);
            }
        }
        double ventanaSeg = duracionSeg > 0
                ? duracionSeg - descartarSeg
                : Duration.between(corte, ultimo).toNanos() / 1e9;

        Map<TipoOperacion, Estadistica> porTipo = new EnumMap<>(TipoOperacion.class);
        grupos.forEach((tipo, lista) -> porTipo.put(tipo, calcular(tipo.name(), lista, ventanaSeg)));
        return new Resultado(porTipo, calcular("TOTAL", solicitudes, ventanaSeg), corte, ultimo, ventanaSeg,
                todos.size(), todos.size() - medidos);
    }

    private static Estadistica calcular(String operacion, List<RegistroTransaccion> registros, double ventanaSeg) {
        long ok = 0, rechazadas = 0, noEnviadas = 0, sinRespuesta = 0, invalidas = 0;
        List<Double> latencias = new ArrayList<>();
        for (RegistroTransaccion r : registros) {
            switch (r.estado()) {
                case OK: ok++; break;
                case RECHAZADO: rechazadas++; break;
                case NO_ENVIADO: noEnviadas++; break;
                case SIN_RESPUESTA: sinRespuesta++; break;
                default: invalidas++; break;
            }
            if (r.estado().esRespondida()) {
                latencias.add(r.latenciaMs());
            }
        }
        long respondidas = ok + rechazadas;
        double promedio = 0, desviacion = 0, minimo = 0, maximo = 0, p95 = 0, p99 = 0, p999 = 0;
        if (!latencias.isEmpty()) {
            double suma = 0;
            for (double l : latencias) {
                suma += l;
            }
            promedio = suma / latencias.size();
            if (latencias.size() > 1) {
                double sumaCuadrados = 0;
                for (double l : latencias) {
                    sumaCuadrados += (l - promedio) * (l - promedio);
                }
                desviacion = Math.sqrt(sumaCuadrados / (latencias.size() - 1));
            }
            Collections.sort(latencias);
            minimo = latencias.get(0);
            maximo = latencias.get(latencias.size() - 1);
            p95 = percentil(latencias, 0.95);
            p99 = percentil(latencias, 0.99);
            p999 = percentil(latencias, 0.999);
        }
        double tpm = ventanaSeg > 0 ? respondidas / (ventanaSeg / 60.0) : 0;
        return new Estadistica(operacion, registros.size(), respondidas, ok, rechazadas,
                noEnviadas, sinRespuesta, invalidas, promedio, desviacion, minimo, maximo, p95, p99, p999, tpm);
    }

    /** Percentil por el método del rango más cercano, sobre una lista ordenada. */
    private static double percentil(List<Double> ordenadas, double fraccion) {
        int indice = (int) Math.ceil(fraccion * ordenadas.size()) - 1;
        return ordenadas.get(Math.max(0, indice));
    }

    /** Formatea las métricas como tabla para la consola y el archivo de resumen. */
    public static String formatear(Resultado res) {
        List<Estadistica> columnas = new ArrayList<>(res.porTipo().values());
        columnas.add(res.total());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Ventana de medición: %.1f s (%d registros", res.ventanaSeg(), res.registrosTotales()));
        if (res.registrosDescartados() > 0) {
            sb.append(String.format(Locale.ROOT, ", %d descartados por calentamiento", res.registrosDescartados()));
        }
        sb.append(")\n\n");
        sb.append(String.format(Locale.ROOT, "%-24s", "Métrica"));
        for (Estadistica e : columnas) {
            sb.append(String.format(Locale.ROOT, " %13s", abreviar(e.operacion())));
        }
        sb.append('\n').append("-".repeat(24));
        for (int i = 0; i < columnas.size(); i++) {
            sb.append(" -------------");
        }
        sb.append('\n');
        filaEntera(sb, "Solicitudes enviadas", columnas, Estadistica::enviadas);
        filaEntera(sb, "Respondidas (procesadas)", columnas, Estadistica::respondidas);
        filaEntera(sb, "  Exitosas (OK)", columnas, Estadistica::ok);
        filaEntera(sb, "  Rechazadas", columnas, Estadistica::rechazadas);
        filaEntera(sb, "Errores técnicos", columnas, Estadistica::erroresTecnicos);
        filaEntera(sb, "  NO_ENVIADO", columnas, Estadistica::noEnviadas);
        filaEntera(sb, "  SIN_RESPUESTA", columnas, Estadistica::sinRespuesta);
        filaEntera(sb, "  RESPUESTA_INVALIDA", columnas, Estadistica::respuestasInvalidas);
        filaDecimal(sb, "Disponibilidad (%)", columnas, Estadistica::disponibilidadPct);
        filaDecimal(sb, "Tiempo promedio (ms)", columnas, Estadistica::promedioMs);
        filaDecimal(sb, "Desviación estándar (ms)", columnas, Estadistica::desviacionMs);
        filaDecimal(sb, "Mínimo (ms)", columnas, Estadistica::minimoMs);
        filaDecimal(sb, "Máximo (ms)", columnas, Estadistica::maximoMs);
        filaDecimal(sb, "Percentil 95 (ms)", columnas, Estadistica::p95Ms);
        filaDecimal(sb, "Percentil 99 (ms)", columnas, Estadistica::p99Ms);
        filaDecimal(sb, "Percentil 99,9 (ms)", columnas, Estadistica::p999Ms);
        filaDecimal(sb, "TPM (procesadas/min)", columnas, Estadistica::tpm);
        sb.append("\nTiempos y TPM solo sobre solicitudes respondidas (OK + RECHAZADO).\n");
        if (res.porTipo().containsKey(TipoOperacion.CANCELACION)) {
            sb.append("CANCELACION mide la respuesta inmediata (ACEPTADA); NOTIF_CANCEL. mide desde el envío\n"
                    + "hasta la llegada de la notificación (CANCELADA = OK, RECHAZADA = rechazada). No suma al total.\n");
        }
        return sb.toString();
    }

    private static String abreviar(String operacion) {
        return operacion.equals("NOTIF_CANCELACION") ? "NOTIF_CANCEL." : operacion;
    }

    private static void filaEntera(StringBuilder sb, String nombre, List<Estadistica> columnas,
                                   Function<Estadistica, Long> campo) {
        sb.append(String.format(Locale.ROOT, "%-24s", nombre));
        for (Estadistica e : columnas) {
            sb.append(String.format(Locale.ROOT, " %13d", campo.apply(e)));
        }
        sb.append('\n');
    }

    private static void filaDecimal(StringBuilder sb, String nombre, List<Estadistica> columnas,
                                    Function<Estadistica, Double> campo) {
        sb.append(String.format(Locale.ROOT, "%-24s", nombre));
        for (Estadistica e : columnas) {
            sb.append(String.format(Locale.ROOT, " %13.3f", campo.apply(e)));
        }
        sb.append('\n');
    }

    /** Recalcula las métricas de un CSV existente. */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Uso: pruebas.AnalizadorMetricas <archivo.csv> [--duracion=segundos] [--descartar=segundos]");
            System.exit(2);
        }
        int descartar = 0;
        double duracion = 0;
        try {
            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--descartar=")) {
                    descartar = Integer.parseInt(args[i].substring("--descartar=".length()).trim());
                } else if (args[i].startsWith("--duracion=")) {
                    duracion = Double.parseDouble(args[i].substring("--duracion=".length()).trim());
                }
            }
            Path archivo = Paths.get(args[0]);
            Resultado res = analizar(leerCsv(archivo), descartar, duracion);
            System.out.println("Archivo: " + archivo.toAbsolutePath());
            if (duracion <= 0) {
                System.out.println("Aviso: sin --duracion la ventana se deduce de los timestamps (TPM aproximado).");
            }
            System.out.print(formatear(res));
        } catch (IOException | RuntimeException e) {
            System.out.println("No se pudo analizar el archivo: " + e.getMessage());
            System.exit(1);
        }
    }
}
