package pruebas;

/** Operaciones generadas por las pruebas de carga. */
public enum TipoOperacion {
    CONSULTA,
    RESERVA,
    MODIFICACION,
    /** Respuesta inmediata (ACEPTADA) a una solicitud de cancelación asíncrona. */
    CANCELACION,
    /** Llegada de la notificación de una cancelación; la latencia se mide desde el envío de CANCELAR. */
    NOTIF_CANCELACION
}
