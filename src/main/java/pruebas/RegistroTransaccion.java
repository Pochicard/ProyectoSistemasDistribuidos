package pruebas;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Registro de una solicitud enviada por un cliente simulado; corresponde a una fila del CSV.
 *
 * <p>Las cinco primeras columnas son las requeridas por el proyecto:</p>
 * <pre>
 * timestamp,cliente_id,tipo_operacion,latencia_ms,estado,solicitud_id,perfil,evento,ocurrencia,cantidad
 * 2026-10-11T15:31:05.123-05:00,C03,RESERVA,4.217,OK,57,MIXTO,E12,2,1
 * </pre>
 * <p>Ningún campo contiene comas, por lo que no se usan comillas.</p>
 *
 * @param solicitudId identificador único de la solicitud dentro de la prueba
 * @param timestamp   instante de envío
 * @param latenciaMs  tiempo entre el envío y la recepción de la respuesta, medido con
 *                    {@code System.nanoTime()}; en los errores, tiempo hasta detectar el fallo
 * @param evento      evento reservado (null en consultas)
 * @param ocurrencia  ocurrencia reservada (null en consultas)
 * @param cantidad    asientos solicitados (null en consultas)
 */
public record RegistroTransaccion(long solicitudId,
                                  Instant timestamp,
                                  String clienteId,
                                  PerfilCliente perfil,
                                  TipoOperacion tipo,
                                  double latenciaMs,
                                  EstadoTransaccion estado,
                                  String evento,
                                  Integer ocurrencia,
                                  Integer cantidad) {

    public static final String ENCABEZADO =
            "timestamp,cliente_id,tipo_operacion,latencia_ms,estado,solicitud_id,perfil,evento,ocurrencia,cantidad";

    /** Encabezado del CSV para Excel en español (separador ';'). */
    public static final String ENCABEZADO_EXCEL = ENCABEZADO.replace(',', ';');

    private static final DateTimeFormatter FORMATO_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter FORMATO_EXCEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Fila del CSV estándar: separador ',', punto decimal y fecha ISO-8601 con zona horaria. */
    public String aCsv() {
        return String.join(",",
                FORMATO_ISO.format(timestamp.atZone(ZoneId.systemDefault())),
                clienteId,
                tipo.name(),
                String.format(Locale.ROOT, "%.3f", latenciaMs),
                estado.name(),
                Long.toString(solicitudId),
                perfil.name(),
                vacioSiNulo(evento),
                vacioSiNulo(ocurrencia),
                vacioSiNulo(cantidad));
    }

    /** Fila del CSV para Excel en español: separador ';' y coma decimal. */
    public String aCsvExcel() {
        return String.join(";",
                FORMATO_EXCEL.format(timestamp.atZone(ZoneId.systemDefault())),
                clienteId,
                tipo.name(),
                String.format(Locale.ROOT, "%.3f", latenciaMs).replace('.', ','),
                estado.name(),
                Long.toString(solicitudId),
                perfil.name(),
                vacioSiNulo(evento),
                vacioSiNulo(ocurrencia),
                vacioSiNulo(cantidad));
    }

    /** Construye un registro a partir de una fila del CSV estándar. */
    public static RegistroTransaccion desdeCsv(String linea) {
        String[] c = linea.split(",", -1);
        if (c.length != 10) {
            throw new IllegalArgumentException("Se esperaban 10 columnas y hay " + c.length + ": " + linea);
        }
        return new RegistroTransaccion(
                Long.parseLong(c[5]),
                OffsetDateTime.parse(c[0], FORMATO_ISO).toInstant(),
                c[1],
                PerfilCliente.valueOf(c[6]),
                TipoOperacion.valueOf(c[2]),
                Double.parseDouble(c[3]),
                EstadoTransaccion.valueOf(c[4]),
                c[7].isEmpty() ? null : c[7],
                c[8].isEmpty() ? null : Integer.valueOf(c[8]),
                c[9].isEmpty() ? null : Integer.valueOf(c[9]));
    }

    /** Instante de finalización de la solicitud (envío + latencia). */
    public Instant fin() {
        return timestamp.plusNanos((long) (latenciaMs * 1_000_000));
    }

    private static String vacioSiNulo(Object valor) {
        return valor == null ? "" : valor.toString();
    }
}
