package pruebas;

import cliente.ErrorComunicacionException;

/**
 * Estado final de una solicitud, registrado en la columna {@code estado} del CSV.
 *
 * <p>Se distinguen dos grupos que se contabilizan por separado en las métricas:</p>
 * <ul>
 *   <li>Respondidas: {@link #OK} y {@link #RECHAZADO}.</li>
 *   <li>Errores técnicos: {@link #NO_ENVIADO}, {@link #SIN_RESPUESTA} y {@link #RESPUESTA_INVALIDA}.</li>
 * </ul>
 */
public enum EstadoTransaccion {

    /** Consulta con JSON válido o reserva aceptada. */
    OK,
    /** Reserva rechazada por el servidor (regla de negocio). */
    RECHAZADO,
    /** La solicitud no pudo enviarse al GT. */
    NO_ENVIADO,
    /** La solicitud se envió, pero no hubo respuesta dentro del tiempo límite. */
    SIN_RESPUESTA,
    /** La respuesta no corresponde al protocolo esperado. */
    RESPUESTA_INVALIDA;

    /** Indica si el servidor procesó la solicitud y respondió según el protocolo. */
    public boolean esRespondida() {
        return this == OK || this == RECHAZADO;
    }

    public boolean esErrorTecnico() {
        return !esRespondida();
    }

    /** Convierte el tipo de error de comunicación del cliente en el estado equivalente. */
    public static EstadoTransaccion desde(ErrorComunicacionException.Tipo tipo) {
        switch (tipo) {
            case NO_ENVIADO:
                return NO_ENVIADO;
            case SIN_RESPUESTA:
                return SIN_RESPUESTA;
            default:
                return RESPUESTA_INVALIDA;
        }
    }
}
