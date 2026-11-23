package cliente;

/**
 * Evento recibido en el JSON de CONSULTA. Los atributos tienen los mismos nombres que en
 * {@code persistencia.Evento} porque Gson los asocia por nombre; Gson omite {@code fecha2} en los eventos
 * de una sola ocurrencia.
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
