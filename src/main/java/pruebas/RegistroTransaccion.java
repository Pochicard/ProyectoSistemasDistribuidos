package pruebas;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Una fila del CSV. Las cinco primeras columnas son las requeridas por el proyecto; la latencia se mide con
 * {@code System.nanoTime()} y la marca temporal con {@code Instant.now()}. Los campos de la reserva quedan
 * vacíos en las consultas.
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
                                  Integer cantidad,
                                  Long idReserva) {

    public static final String ENCABEZADO = "timestamp,cliente_id,tipo_operacion,latencia_ms,estado,"
            + "solicitud_id,perfil,evento,ocurrencia,cantidad,id_reserva";

    /** Encabezado de los CSV de la primera entrega (sin id_reserva); se siguen pudiendo leer. */
    public static final String ENCABEZADO_V1 =
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
                vacioSiNulo(cantidad),
                vacioSiNulo(idReserva));
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
                vacioSiNulo(cantidad),
                vacioSiNulo(idReserva));
    }

    /** Construye un registro a partir de una fila del CSV estándar. */
    public static RegistroTransaccion desdeCsv(String linea) {
        String[] c = linea.split(",", -1);
        if (c.length != 10 && c.length != 11) {
            throw new IllegalArgumentException("Se esperaban 10 u 11 columnas y hay " + c.length + ": " + linea);
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
                c[9].isEmpty() ? null : Integer.valueOf(c[9]),
                c.length < 11 || c[10].isEmpty() ? null : Long.valueOf(c[10]));
    }

    /** Instante de finalización de la solicitud (envío + latencia). */
    public Instant fin() {
        return timestamp.plusNanos((long) (latenciaMs * 1_000_000));
    }

    private static String vacioSiNulo(Object valor) {
        return valor == null ? "" : valor.toString();
    }
}
