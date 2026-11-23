package pruebas;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reservas creadas durante la prueba, compartidas entre los clientes y el hilo de notificaciones
 * (ConcurrentHashMap y campos volatile). Permite calcular los asientos que deberían estar ocupados.
 */
final class RegistroReservas {

    enum Estado { ACTIVA, CANCELANDO, CANCELADA }

    /** Estado conocido de una reserva hecha por un cliente simulado. */
    static final class Reserva {
        final String clienteId;
        final String evento;
        volatile int ocurrencia;
        volatile int cantidad;
        volatile Estado estado = Estado.ACTIVA;

        Reserva(String clienteId, String evento, int ocurrencia, int cantidad) {
            this.clienteId = clienteId;
            this.evento = evento;
            this.ocurrencia = ocurrencia;
            this.cantidad = cantidad;
        }
    }

    /** Cancelación aceptada cuya notificación aún no ha llegado. */
    record CancelacionPendiente(long solicitudId, String clienteId, PerfilCliente perfil, java.time.Instant marca,
                                long envioNanos, String evento, int ocurrencia, int cantidad) {
    }

    private final Map<Long, Reserva> reservas = new ConcurrentHashMap<>();
    private final Map<Long, CancelacionPendiente> pendientes = new ConcurrentHashMap<>();

    void agregar(long idReserva, Reserva reserva) {
        reservas.put(idReserva, reserva);
    }

    Reserva obtener(long idReserva) {
        return reservas.get(idReserva);
    }

    void registrarCancelacionPendiente(long idReserva, CancelacionPendiente pendiente) {
        pendientes.put(idReserva, pendiente);
    }

    /** Retira y devuelve la cancelación pendiente de una reserva, o null si no hay ninguna. */
    CancelacionPendiente retirarCancelacionPendiente(long idReserva) {
        return pendientes.remove(idReserva);
    }

    Map<Long, CancelacionPendiente> pendientes() {
        return pendientes;
    }

    int totalReservas() {
        return reservas.size();
    }

    /**
     * Asientos que deberían estar ocupados por ocurrencia ("E5:1") según lo que sabe el generador.
     * Una reserva con cancelación aceptada pero sin notificación cuenta como ocupada.
     */
    Map<String, Long> asientosOcupadosPorOcurrencia() {
        Map<String, Long> ocupados = new HashMap<>();
        for (Reserva r : reservas.values()) {
            if (r.estado != Estado.CANCELADA) {
                ocupados.merge(r.evento + ":" + r.ocurrencia, (long) r.cantidad, Long::sum);
            }
        }
        return ocupados;
    }
}
