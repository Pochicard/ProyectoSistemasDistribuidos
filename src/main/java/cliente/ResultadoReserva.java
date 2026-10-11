package cliente;

/**
 * Resultado de negocio de una solicitud RESERVA que SÍ obtuvo respuesta del GT.
 * Los fallos técnicos (sin conexión, sin respuesta) no llegan aquí: se representan con
 * {@link ErrorComunicacionException}.
 */
public enum ResultadoReserva {

    /** El servidor respondió "OK": los asientos se descontaron. */
    EXITOSA,

    /**
     * El servidor respondió "RECHAZADO". Con el backend actual significa cualquiera de:
     * no hay asientos suficientes, el evento no existe o la ocurrencia no existe.
     * El protocolo no permite distinguir entre estos casos.
     */
    RECHAZADA;

    /**
     * Traduce la respuesta textual del servidor.
     *
     * @throws ErrorComunicacionException RESPUESTA_INVALIDA si es "ERROR..." o un texto desconocido
     */
    public static ResultadoReserva desde(String respuesta) throws ErrorComunicacionException {
        if (ProtocoloSrb.RESPUESTA_OK.equals(respuesta)) {
            return EXITOSA;
        }
        if (ProtocoloSrb.RESPUESTA_RECHAZADO.equals(respuesta)) {
            return RECHAZADA;
        }
        if (ProtocoloSrb.esError(respuesta)) {
            throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA,
                    "El servidor devolvió un error: " + respuesta);
        }
        throw new ErrorComunicacionException(ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA,
                "Respuesta inesperada a una reserva: \"" + respuesta + "\"");
    }
}
