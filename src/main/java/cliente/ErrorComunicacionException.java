package cliente;

/**
 * Error técnico al comunicarse con el Gestor de Transacciones (GT).
 *
 * <p>Se separa de las respuestas de negocio ({@code OK} / {@code RECHAZADO}):
 * un rechazo significa que el sistema SÍ procesó la solicitud y dijo "no";
 * un error de comunicación significa que no sabemos qué pasó o que el GT
 * respondió algo que no cumple el protocolo.</p>
 */
public class ErrorComunicacionException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Clasificación del fallo; el generador de carga la usará como "estado" en el CSV. */
    public enum Tipo {
        /** El mensaje nunca salió del cliente (no hay conexión con el GT). No pudo procesarse. */
        NO_ENVIADO,
        /** El mensaje salió pero no llegó respuesta dentro del tiempo límite. Pudo o no procesarse. */
        SIN_RESPUESTA,
        /** Llegó una respuesta, pero no corresponde al protocolo esperado (JSON inválido, ERROR..., etc.). */
        RESPUESTA_INVALIDA
    }

    private final Tipo tipo;

    public ErrorComunicacionException(Tipo tipo, String mensaje) {
        super(mensaje);
        this.tipo = tipo;
    }

    public ErrorComunicacionException(Tipo tipo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.tipo = tipo;
    }

    public Tipo getTipo() {
        return tipo;
    }

    /**
     * Indica si existe la posibilidad de que el servidor haya ejecutado la operación.
     * Es clave para decidir si una RESERVA se puede reintentar sin riesgo de duplicarla.
     */
    public boolean pudoHaberseProcesado() {
        return tipo != Tipo.NO_ENVIADO;
    }
}
