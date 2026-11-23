package comun;

/**
 * Contrato de mensajes del Sistema de Reserva de Boletos (SRB), compartido por todos los procesos.
 *
 * <p>Todos los mensajes son texto UTF-8 en una sola trama ZeroMQ, con los campos separados por
 * {@code :}. {@code idSol} es un identificador unico (UUID) que genera el cliente para cada
 * operacion y repite igual en los reintentos: la Persistencia lo usa para no aplicar dos veces
 * la misma operacion (idempotencia).</p>
 *
 * <pre>
 * Solicitud del cliente (al GT)                                   Respuesta
 * ------------------------------------------------------------    ------------------------------
 * CONSULTA                       (formato de la 1.a entrega)      JSON con los eventos
 * CONSULTA:&lt;idSol&gt;                                               JSON con los eventos
 * RESERVA:&lt;evento&gt;:&lt;ocur&gt;:&lt;cant&gt; (formato de la 1.a entrega)      OK | RECHAZADO
 * RESERVA:&lt;idSol&gt;:&lt;evento&gt;:&lt;ocur&gt;:&lt;cant&gt;                         OK:&lt;idReserva&gt; | RECHAZADO
 * MODIFICAR:&lt;idSol&gt;:&lt;idReserva&gt;:&lt;ocurNueva&gt;:&lt;cantNueva&gt;          OK | RECHAZADO
 * CANCELAR:&lt;idSol&gt;:&lt;idReserva&gt;:&lt;ipCliente&gt;:&lt;puertoNotif&gt;       ACEPTADA  (de inmediato)
 *     ... y despues, en el socket PULL del cliente:               NOTIF:&lt;idReserva&gt;:CANCELADA
 *                                                                 NOTIF:&lt;idReserva&gt;:RECHAZADA
 *
 * Interno (Servicio de Cancelaciones -&gt; Persistencia)
 * LIBERAR:&lt;idSol&gt;:&lt;idReserva&gt;                                     OK | RECHAZADO
 *
 * Cualquier error de formato o de servicio                         ERROR:&lt;explicacion&gt;
 * </pre>
 *
 * <p>Los formatos de la primera entrega (sin {@code idSol}) se siguen aceptando para que el
 * cliente y el generador de carga anteriores funcionen sin cambios; esas operaciones no tienen
 * proteccion contra duplicados.</p>
 */
public final class Protocolo {

    public static final String SEP = ":";

    public static final String CONSULTA = "CONSULTA";
    public static final String RESERVA = "RESERVA";
    public static final String MODIFICAR = "MODIFICAR";
    public static final String CANCELAR = "CANCELAR";
    public static final String LIBERAR = "LIBERAR";
    public static final String NOTIF = "NOTIF";

    public static final String OK = "OK";
    public static final String RECHAZADO = "RECHAZADO";
    public static final String ACEPTADA = "ACEPTADA";
    public static final String CANCELADA = "CANCELADA";
    public static final String RECHAZADA = "RECHAZADA";
    public static final String ERROR = "ERROR";

    private Protocolo() {
    }

    /** Operacion de un mensaje: el texto antes del primer {@code :} (o el mensaje completo). */
    public static String operacion(String mensaje) {
        int i = mensaje.indexOf(SEP);
        return i < 0 ? mensaje : mensaje.substring(0, i);
    }

    public static String error(String explicacion) {
        return ERROR + SEP + explicacion;
    }

    public static String notificacion(String idReserva, boolean cancelada) {
        return NOTIF + SEP + idReserva + SEP + (cancelada ? CANCELADA : RECHAZADA);
    }

    /** Respuesta exitosa de una reserva: "OK:&lt;idReserva&gt;" (formato nuevo) u "OK" (formato anterior). */
    public static String okReserva(long idReserva, boolean formatoNuevo) {
        return formatoNuevo ? OK + SEP + idReserva : OK;
    }
}
