package persistencia;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BaseDatos {
    private final Map<String, Evento> eventos = new ConcurrentHashMap<>();

    public BaseDatos() {
        cargarEventosIniciales();
    }

    private void cargarEventosIniciales() {
        for (int i = 1; i <= 20; i++) {
            String id = "E" + i;
            eventos.put(id, new Evento(id, 2, "2026-11-10", "2026-11-20"));
        }
        for (int i = 21; i <= 50; i++) {
            String id = "E" + i;
            eventos.put(id, new Evento(id, 1, "2026-11-15", null));
        }
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