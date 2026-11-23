package cliente;

/**
 * Resultado de negocio de una reserva o modificación que obtuvo respuesta del GT.
 * Los fallos técnicos se representan con {@link ErrorComunicacionException}.
 */
public enum ResultadoReserva {

    /** "OK" u "OK:&lt;idReserva&gt;": la operación se aplicó. */
    EXITOSA,

    /** "RECHAZADO": sin cupo, reserva inexistente o cancelada, u ocurrencia inexistente. */
    RECHAZADA;

    // Traduce la respuesta del servidor; "ERROR..." o un texto desconocido es RESPUESTA_INVALIDA.
    public static ResultadoReserva desde(String respuesta) throws ErrorComunicacionException {
        if (ProtocoloSrb.RESPUESTA_OK.equals(respuesta)
                || (respuesta != null && respuesta.startsWith(ProtocoloSrb.RESPUESTA_OK + ProtocoloSrb.SEPARADOR))) {
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
                "Respuesta inesperada: \"" + respuesta + "\"");
    }
}
