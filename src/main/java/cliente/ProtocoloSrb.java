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

/**
 * Contrato de mensajes entre el cliente y el Gestor de Transacciones, tal como está
 * implementado HOY en gt.GtMain, rmc.RmcMain y persistencia.PersistenciaMain.
 *
 * <pre>
 * Solicitud                      Respuesta
 * ---------------------------    ---------------------------------------------
 * CONSULTA                       JSON: {"E1":{...}, "E2":{...}, ...}  (los 50 eventos)
 * RESERVA:&lt;id&gt;:&lt;ocur&gt;:&lt;cant&gt;     "OK" | "RECHAZADO"
 * (cualquier otro texto)         "ERROR: ..."
 * </pre>
 *
 * <p>Todo es texto UTF-8 en un único frame ZeroMQ. Esta clase no usa la red: solo
 * construye e interpreta mensajes, así que puede probarse sin levantar los servicios.</p>
 */
public final class ProtocoloSrb {

    public static final String CONSULTA = "CONSULTA";
    public static final String PREFIJO_RESERVA = "RESERVA";
    public static final String SEPARADOR = ":";
    public static final String RESPUESTA_OK = "OK";
    public static final String RESPUESTA_RECHAZADO = "RECHAZADO";
    public static final String PREFIJO_ERROR = "ERROR";

    private static final Gson GSON = new Gson();
    private static final Type TIPO_MAPA_EVENTOS = new TypeToken<Map<String, EventoDto>>() { }.getType();

    private ProtocoloSrb() {
    }

    /** Construye "RESERVA:E1:1:2". Los datos deben venir ya validados por {@link ValidadorEntrada}. */
    public static String mensajeReserva(String idEvento, int ocurrencia, int cantidad) {
        if (idEvento == null || idEvento.contains(SEPARADOR)) {
            // Un ':' dentro del id desplazaría los campos del mensaje.
            throw new IllegalArgumentException("Identificador de evento inválido: " + idEvento);
        }
        return PREFIJO_RESERVA + SEPARADOR + idEvento + SEPARADOR + ocurrencia + SEPARADOR + cantidad;
    }

    public static boolean esError(String respuesta) {
        return respuesta != null && respuesta.startsWith(PREFIJO_ERROR);
    }

    /**
     * Convierte la respuesta de CONSULTA en un mapa ordenado E1, E2, …, E50.
     * (La Persistencia usa ConcurrentHashMap, que no garantiza ningún orden.)
     *
     * @throws ErrorComunicacionException RESPUESTA_INVALIDA si llega "ERROR..." o JSON mal formado
     */
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
