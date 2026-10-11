package cliente;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validaciones que hace el CLIENTE antes de enviar una solicitud.
 *
 * <p>Importante para la sustentación: estas validaciones protegen al sistema de errores del
 * usuario de ESTE cliente, pero no son una garantía del sistema. El servidor actual no valida
 * la cantidad (acepta 0 y negativos) y se cae con mensajes mal formados, así que otro cliente
 * podría seguir provocando esos problemas. Ver el reporte de defectos del backend.</p>
 *
 * <p>Cada método lanza {@link IllegalArgumentException} con un mensaje listo para mostrar.</p>
 */
public final class ValidadorEntrada {

    /** "E" seguido de un número sin ceros a la izquierda: E1 … E50 (o más, si cambia el catálogo). */
    private static final Pattern FORMATO_ID = Pattern.compile("E[1-9][0-9]*");

    private ValidadorEntrada() {
    }

    /**
     * Normaliza ("e7 " → "E7"), valida el formato y comprueba que exista en el catálogo.
     */
    public static String validarIdEvento(String entrada, Map<String, EventoDto> catalogo) {
        String id = entrada == null ? "" : entrada.trim().toUpperCase(Locale.ROOT);
        if (!FORMATO_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Identificador inválido: \"" + (entrada == null ? "" : entrada.trim())
                            + "\". Use el formato E<número>, por ejemplo E7.");
        }
        if (!catalogo.containsKey(id)) {
            throw new IllegalArgumentException("El evento " + id + " no existe en el catálogo consultado.");
        }
        return id;
    }

    /** Valida que la ocurrencia sea un entero entre 1 y el número de ocurrencias del evento. */
    public static int validarOcurrencia(String entrada, EventoDto evento) {
        int ocurrencia = parsearEntero(entrada, "La ocurrencia");
        if (!evento.esOcurrenciaValida(ocurrencia)) {
            throw new IllegalArgumentException("El evento " + evento.getId() + " solo tiene "
                    + evento.getOcurrencias() + (evento.getOcurrencias() == 1 ? " ocurrencia (use 1)."
                    : " ocurrencias (use un valor entre 1 y " + evento.getOcurrencias() + ")."));
        }
        return ocurrencia;
    }

    /** Valida que la cantidad sea un entero estrictamente positivo. */
    public static int validarCantidad(String entrada) {
        int cantidad = parsearEntero(entrada, "La cantidad");
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad de asientos debe ser mayor que cero.");
        }
        return cantidad;
    }

    private static int parsearEntero(String entrada, String campo) {
        String texto = entrada == null ? "" : entrada.trim();
        try {
            return Integer.parseInt(texto);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(campo + " debe ser un número entero: \"" + texto + "\".");
        }
    }
}
