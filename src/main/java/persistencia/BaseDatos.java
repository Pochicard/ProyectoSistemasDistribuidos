package persistencia;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BaseDatos {
    private final Map<String, Evento> eventos = new ConcurrentHashMap<>();

    public BaseDatos() {
        cargarEventosIniciales();
    }

    private void cargarEventosIniciales() {
        eventos.put("E1", new Evento("E1", 1, "2026-11-10", "2026-11-20"));
        eventos.put("E2", new Evento("E2", 1, "2026-11-15", "2026-11-25"));
    }

    public Map<String, Evento> getEventos() {
        return eventos;
    }

    public boolean hacerReserva(String idEvento, int ocurrencia, int cantidad) {
        Evento evento = eventos.get(idEvento);
        if (evento != null) {
            return evento.reservar(ocurrencia, cantidad);
        }
        return false;
    }
}