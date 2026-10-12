package pruebas;

import cliente.EventoDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Selecciona el evento y la ocurrencia de destino de cada reserva.
 *
 * <p>Las ocurrencias se obtienen de una consulta real al sistema, de modo que solo se reservan
 * ocurrencias existentes (70 con la base de datos actual).</p>
 *
 * <p>Cada cliente tiene su propio selector y su propio {@link Random}; la clase no se comparte entre hilos.</p>
 */
public final class SelectorOcurrencia {

    /** Estrategias de selección (parámetro {@code --seleccion}). */
    public enum Estrategia {
        /** Ocurrencia aleatoria uniforme; reparte la carga y evita agotar asientos. */
        ALEATORIA,
        /** Recorrido secuencial; cada cliente inicia en una posición distinta. */
        ROTACION,
        /** Ocurrencia única; se usa en la prueba de contención. */
        FIJA
    }

    /** Ocurrencia concreta de un evento, por ejemplo E12:2. */
    public record Ocurrencia(String evento, int numero) {
        @Override
        public String toString() {
            return evento + ":" + numero;
        }
    }

    private final List<Ocurrencia> ocurrencias;
    private final Estrategia estrategia;
    private final Random aleatorio;
    private int posicion;

    SelectorOcurrencia(List<Ocurrencia> ocurrencias, Estrategia estrategia, Random aleatorio, int desplazamiento) {
        if (ocurrencias.isEmpty()) {
            throw new IllegalArgumentException("No hay ocurrencias para reservar");
        }
        this.ocurrencias = ocurrencias;
        this.estrategia = estrategia;
        this.aleatorio = aleatorio;
        this.posicion = Math.floorMod(desplazamiento, ocurrencias.size());
    }

    public Ocurrencia siguiente() {
        switch (estrategia) {
            case ALEATORIA:
                return ocurrencias.get(aleatorio.nextInt(ocurrencias.size()));
            case ROTACION:
                Ocurrencia o = ocurrencias.get(posicion);
                posicion = (posicion + 1) % ocurrencias.size();
                return o;
            default: // FIJA: la lista contiene una sola ocurrencia
                return ocurrencias.get(0);
        }
    }

    /** Lista de todas las ocurrencias del catálogo, en el orden E1:1, E1:2, E2:1, ..., E50:1. */
    public static List<Ocurrencia> desdeCatalogo(Map<String, EventoDto> catalogo) {
        List<Ocurrencia> lista = new ArrayList<>();
        for (EventoDto e : catalogo.values()) {
            for (int o = 1; o <= e.getOcurrencias(); o++) {
                lista.add(new Ocurrencia(e.getId(), o));
            }
        }
        return Collections.unmodifiableList(lista);
    }
}
