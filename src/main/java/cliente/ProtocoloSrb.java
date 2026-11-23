package cliente;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Construcción e interpretación de los mensajes cliente–GT (contrato de {@code comun.Protocolo}).
 *
 * <pre>
 * CONSULTA:&lt;idSol&gt;                                → JSON con los eventos
 * RESERVA:&lt;idSol&gt;:&lt;evento&gt;:&lt;ocur&gt;:&lt;cant&gt;          → OK:&lt;idReserva&gt; | RECHAZADO
 * MODIFICAR:&lt;idSol&gt;:&lt;idReserva&gt;:&lt;ocur&gt;:&lt;cant&gt;     → OK | RECHAZADO
 * CANCELAR:&lt;idSol&gt;:&lt;idReserva&gt;:&lt;ip&gt;:&lt;puerto&gt;      → ACEPTADA; luego NOTIF:&lt;idReserva&gt;:CANCELADA|RECHAZADA
 * </pre>
 * <p>Se conservan también los formatos sin idSol de la primera entrega.</p>
 */
public final class ProtocoloSrb {

    public static final String CONSULTA = "CONSULTA";
    public static final String PREFIJO_RESERVA = "RESERVA";
    public static final String PREFIJO_MODIFICAR = "MODIFICAR";
    public static final String PREFIJO_CANCELAR = "CANCELAR";
    public static final String PREFIJO_NOTIFICACION = "NOTIF";
    public static final String SEPARADOR = ":";
    public static final String RESPUESTA_OK = "OK";
    public static final String RESPUESTA_RECHAZADO = "RECHAZADO";
    public static final String RESPUESTA_ACEPTADA = "ACEPTADA";
    public static final String NOTIF_CANCELADA = "CANCELADA";
    public static final String NOTIF_RECHAZADA = "RECHAZADA";
    public static final String PREFIJO_ERROR = "ERROR";

    private static final Gson GSON = new Gson();
    private static final Type TIPO_MAPA_EVENTOS = new TypeToken<Map<String, EventoDto>>() { }.getType();

    /** Notificación del Servicio de Cancelaciones sobre una reserva. */
    public record Notificacion(long idReserva, boolean cancelada) {
    }

    private ProtocoloSrb() {
    }

    /** Identificador único para una operación; se reutiliza en sus reintentos. */
    public static String nuevoIdSolicitud() {
        return UUID.randomUUID().toString();
    }

    public static String mensajeConsulta(String idSol) {
        return CONSULTA + SEPARADOR + validarCampo(idSol, "idSol");
    }

    /** Reserva con el formato de la primera entrega: "RESERVA:E1:1:2". */
    public static String mensajeReserva(String idEvento, int ocurrencia, int cantidad) {
        return PREFIJO_RESERVA + SEPARADOR + validarCampo(idEvento, "evento") + SEPARADOR + ocurrencia
                + SEPARADOR + cantidad;
    }

    /** Reserva con idSol: "RESERVA:&lt;idSol&gt;:E1:1:2". */
    public static String mensajeReserva(String idSol, String idEvento, int ocurrencia, int cantidad) {
        return PREFIJO_RESERVA + SEPARADOR + validarCampo(idSol, "idSol") + SEPARADOR
                + validarCampo(idEvento, "evento") + SEPARADOR + ocurrencia + SEPARADOR + cantidad;
    }

    public static String mensajeModificar(String idSol, long idReserva, int ocurrenciaNueva, int cantidadNueva) {
        return PREFIJO_MODIFICAR + SEPARADOR + validarCampo(idSol, "idSol") + SEPARADOR + idReserva
                + SEPARADOR + ocurrenciaNueva + SEPARADOR + cantidadNueva;
    }

    public static String mensajeCancelar(String idSol, long idReserva, String ipCliente, int puertoNotificacion) {
        return PREFIJO_CANCELAR + SEPARADOR + validarCampo(idSol, "idSol") + SEPARADOR + idReserva
                + SEPARADOR + validarCampo(ipCliente, "ip") + SEPARADOR + puertoNotificacion;
    }

    /** Un ':' dentro de un campo desplazaría los demás campos del mensaje. */
    private static String validarCampo(String valor, String nombre) {
        if (valor == null || valor.isEmpty() || valor.contains(SEPARADOR)) {
            throw new IllegalArgumentException("Valor inválido para " + nombre + ": " + valor);
        }
        return valor;
    }

    public static boolean esError(String respuesta) {
        return respuesta != null && respuesta.startsWith(PREFIJO_ERROR);
    }

    // Extrae el id de "OK:<idReserva>"; devuelve -1 si no viene (formato de la primera entrega).
    public static long idReserva(String respuesta) {
        String prefijo = RESPUESTA_OK + SEPARADOR;
        if (respuesta == null || !respuesta.startsWith(prefijo)) {
            return -1;
        }
        try {
            return Long.parseLong(respuesta.substring(prefijo.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Interpreta "NOTIF:&lt;idReserva&gt;:CANCELADA|RECHAZADA"; devuelve null si el formato no es válido. */
    public static Notificacion parsearNotificacion(String mensaje) {
        if (mensaje == null) {
            return null;
        }
        String[] p = mensaje.split(SEPARADOR, -1);
        if (p.length != 3 || !p[0].equals(PREFIJO_NOTIFICACION)
                || !(p[2].equals(NOTIF_CANCELADA) || p[2].equals(NOTIF_RECHAZADA))) {
            return null;
        }
        try {
            return new Notificacion(Long.parseLong(p[1]), p[2].equals(NOTIF_CANCELADA));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Convierte la respuesta de CONSULTA en un mapa ordenado E1..E50.
    public static Map<String, EventoDto> parsearConsulta(String respuesta) throws ErrorComunicacionException {
        if (esError(respuesta)) {
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA,
                    "El servidor devolvió un error: " + respuesta);
        }
        Map<String, EventoDto> crudo;
        try {
            crudo = GSON.fromJson(respuesta, TIPO_MAPA_EVENTOS);
        } catch (JsonParseException e) {
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA,
                    "La respuesta a CONSULTA no es un JSON válido", e);
        }
        if (crudo == null) {
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA,
                    "La respuesta a CONSULTA llegó vacía");
        }

        List<EventoDto> lista = new ArrayList<>(crudo.values());
        lista.sort(Comparator.comparingInt(EventoDto::getNumero));
        Map<String, EventoDto> ordenado = new LinkedHashMap<>();
        for (EventoDto e : lista) {
            ordenado.put(e.getId(), e);
        }
        return ordenado;
    }
}
