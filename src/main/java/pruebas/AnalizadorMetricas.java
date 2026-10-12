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
import java.util.List;
import java.util.Locale;

/**
 * Cálculo de métricas de rendimiento a partir de un CSV de resultados.
 *
 * <p>El generador usa esta misma clase para su resumen, de modo que el CSV es la única fuente de datos.</p>
 *
 * <p>Definiciones, por operación (CONSULTA, RESERVA) y en total:</p>
 * <ul>
 *   <li>Enviadas: todas las solicitudes registradas.</li>
 *   <li>Respondidas (procesadas): OK + RECHAZADO.</li>
 *   <li>Errores técnicos: NO_ENVIADO + SIN_RESPUESTA + RESPUESTA_INVALIDA; se excluyen de tiempos y TPM.</li>
 *   <li>Tiempo promedio y desviación estándar muestral (n − 1): calculados sobre las respondidas.</li>
 *   <li>TPM: respondidas por minuto de la ventana de medición.</li>
 *   <li>Ventana: duración de la prueba menos los segundos descartados. Si no se indica la duración,
 *       se toma desde el primer envío hasta la última respuesta.</li>
 * </ul>
 *
 * <p>Uso: {@code pruebas.AnalizadorMetricas <archivo.csv> --duracion=150 [--descartar=10]}</p>
 */
public final class AnalizadorMetricas {

    /** Métricas de un tipo de operación o del total. */
    public record Estadistica(String operacion, long enviadas, long respondidas, long ok, long rechazadas,
                              long noEnviadas, long sinRespuesta, long respuestasInvalidas,
                              double promedioMs, double desviacionMs, double minimoMs, double maximoMs,
                              double p95Ms, double tpm) {

        public long erroresTecnicos() {
            return noEnviadas + sinRespuesta + respuestasInvalidas;
        }
    }

    /** Resultado completo del análisis de un CSV. */
    public record Resultado(Estadistica consulta, Estadistica reserva, Estadistica total,
                            Instant inicioVentana, Instant finVentana, double ventanaSeg,
                            int registrosTotales, int registrosDescartados) {
    }

    private AnalizadorMetricas() {
    }

    /** Lee las filas de un CSV estándar generado por {@link EscritorCsv}. */
    public static List<RegistroTransaccion> leerCsv(Path archivo) throws IOException {
        List<RegistroTransaccion> registros = new ArrayList<>();
        try (BufferedReader lector = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            String encabezado = lector.readLine();
            if (!RegistroTransaccion.ENCABEZADO.equals(encabezado)) {
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

    /**
     * Calcula las métricas de los registros.
     *
     * @param descartarSeg segundos iniciales excluidos (calentamiento); 0 para usar todos
     * @param duracionSeg  duración de la prueba; si es 0 o menor, la ventana se deduce de los timestamps
     */
    public static Resultado analizar(List<RegistroTransaccion> todos, int descartarSeg, double duracionSeg) {
        if (todos.isEmpty()) {
            Estadistica vacia = calcular("-", List.of(), 0);
            return new Resultado(vacia, vacia, vacia, null, null, 0, 0, 0);
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
        List<RegistroTransaccion> medidos = new ArrayList<>();
        for (RegistroTransaccion r : todos) {
            if (!r.timestamp().isBefore(corte)) {
                medidos.add(r);
            }
        }
        double ventanaSeg = duracionSeg > 0
                ? duracionSeg - descartarSeg
                : Duration.between(corte, ultimo).toNanos() / 1e9;

        List<RegistroTransaccion> consultas = new ArrayList<>();
        List<RegistroTransaccion> reservas = new ArrayList<>();
        for (RegistroTransaccion r : medidos) {
            (r.tipo() == TipoOperacion.CONSULTA ? consultas : reservas).add(r);
        }
        return new Resultado(
                calcular("CONSULTA", consultas, ventanaSeg),
                calcular("RESERVA", reservas, ventanaSeg),
                calcular("TOTAL", medidos, ventanaSeg),
                corte, ultimo, ventanaSeg, todos.size(), todos.size() - medidos.size());
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
        double promedio = 0, desviacion = 0, minimo = 0, maximo = 0, p95 = 0;
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
            // Percentil 95 por el método del rango más cercano.
            int indice = (int) Math.ceil(0.95 * latencias.size()) - 1;
            p95 = latencias.get(Math.max(0, indice));
        }
        double tpm = ventanaSeg > 0 ? respondidas / (ventanaSeg / 60.0) : 0;
        return new Estadistica(operacion, registros.size(), respondidas, ok, rechazadas,
                noEnviadas, sinRespuesta, invalidas, promedio, desviacion, minimo, maximo, p95, tpm);
    }

    /** Formatea las métricas como tabla para la consola y el archivo de resumen. */
    public static String formatear(Resultado res) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Ventana de medición: %.1f s (%d registros", res.ventanaSeg(), res.registrosTotales()));
        if (res.registrosDescartados() > 0) {
            sb.append(String.format(Locale.ROOT, ", %d descartados por calentamiento", res.registrosDescartados()));
        }
        sb.append(")\n\n");
        sb.append(String.format(Locale.ROOT, "%-24s %12s %12s %12s%n", "Métrica", "CONSULTA", "RESERVA", "TOTAL"));
        sb.append("------------------------ ------------ ------------ ------------\n");
        filaEntera(sb, "Solicitudes enviadas", res, Estadistica::enviadas);
        filaEntera(sb, "Respondidas (procesadas)", res, Estadistica::respondidas);
        filaEntera(sb, "  Exitosas (OK)", res, Estadistica::ok);
        filaEntera(sb, "  Rechazadas", res, Estadistica::rechazadas);
        filaEntera(sb, "Errores técnicos", res, Estadistica::erroresTecnicos);
        filaEntera(sb, "  NO_ENVIADO", res, Estadistica::noEnviadas);
        filaEntera(sb, "  SIN_RESPUESTA", res, Estadistica::sinRespuesta);
        filaEntera(sb, "  RESPUESTA_INVALIDA", res, Estadistica::respuestasInvalidas);
        filaDecimal(sb, "Tiempo promedio (ms)", res, Estadistica::promedioMs);
        filaDecimal(sb, "Desviación estándar (ms)", res, Estadistica::desviacionMs);
        filaDecimal(sb, "Mínimo (ms)", res, Estadistica::minimoMs);
        filaDecimal(sb, "Máximo (ms)", res, Estadistica::maximoMs);
        filaDecimal(sb, "Percentil 95 (ms)", res, Estadistica::p95Ms);
        filaDecimal(sb, "TPM (procesadas/min)", res, Estadistica::tpm);
        sb.append("\nTiempos y TPM se calculan solo sobre solicitudes respondidas (OK + RECHAZADO).\n");
        return sb.toString();
    }

    private interface Campo<T> {
        T de(Estadistica e);
    }

    private static void filaEntera(StringBuilder sb, String nombre, Resultado r, Campo<Long> campo) {
        sb.append(String.format(Locale.ROOT, "%-24s %12d %12d %12d%n", nombre,
                campo.de(r.consulta()), campo.de(r.reserva()), campo.de(r.total())));
    }

    private static void filaDecimal(StringBuilder sb, String nombre, Resultado r, Campo<Double> campo) {
        sb.append(String.format(Locale.ROOT, "%-24s %12.3f %12.3f %12.3f%n", nombre,
                campo.de(r.consulta()), campo.de(r.reserva()), campo.de(r.total())));
    }

    /** Recalcula las métricas de un CSV existente. */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Uso: pruebas.AnalizadorMetricas <archivo.csv> [--descartar=segundos]");
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
