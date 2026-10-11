package cliente;

/**
 * Copia en el cliente de un evento tal como lo serializa la Persistencia con Gson.
 *
 * <p>Los nombres de los atributos deben coincidir EXACTAMENTE con los de
 * {@code persistencia.Evento}, porque Gson los asocia por nombre. Ejemplo real de la respuesta:</p>
 * <pre>
 * "E1":  {"id":"E1","ocurrencias":2,"fecha1":"2026-11-10","fecha2":"2026-11-20",
 *         "asientosDisponibles1":100,"asientosDisponibles2":100}
 * "E21": {"id":"E21","ocurrencias":1,"fecha1":"2026-11-15",
 *         "asientosDisponibles1":100,"asientosDisponibles2":0}
 * </pre>
 * <p>Gson omite los valores null, por eso los eventos de una ocurrencia no traen {@code fecha2}.</p>
 *
 * <p>Se usa una clase propia del cliente (y no {@code persistencia.Evento}) para que el
 * cliente no dependa del código interno del servidor: solo del contrato JSON.</p>
 */
public class EventoDto {

    private String id;
    private int ocurrencias;
    private String fecha1;
    private String fecha2;
    private int asientosDisponibles1;
    private int asientosDisponibles2;

    public String getId() {
        return id;
    }

    public int getOcurrencias() {
        return ocurrencias;
    }

    /** @return true si la ocurrencia está entre 1 y el número de ocurrencias del evento */
    public boolean esOcurrenciaValida(int ocurrencia) {
        return ocurrencia >= 1 && ocurrencia <= ocurrencias;
    }

    /** Fecha de la ocurrencia 1 o 2 ("—" si el servidor no la envió). */
    public String getFecha(int ocurrencia) {
        String fecha = (ocurrencia == 1) ? fecha1 : (ocurrencia == 2 ? fecha2 : null);
        return fecha != null ? fecha : "—";
    }

    /** Asientos disponibles en la ocurrencia 1 o 2. */
    public int getDisponibles(int ocurrencia) {
        if (ocurrencia == 1) {
            return asientosDisponibles1;
        }
        if (ocurrencia == 2) {
            return asientosDisponibles2;
        }
        throw new IllegalArgumentException("Ocurrencia inexistente: " + ocurrencia);
    }

    /** Número del identificador ("E12" → 12), útil para ordenar E1, E2, …, E50. */
    public int getNumero() {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE; // identificadores inesperados al final
        }
    }
}
