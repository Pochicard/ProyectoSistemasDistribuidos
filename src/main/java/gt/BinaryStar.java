package gt;

/**
 * Maquina de estados del patron <b>Binary Star</b> (guia de ZeroMQ, capitulo 4) para el par
 * GT primario / GT de respaldo.
 *
 * <p>Cada GT publica su estado cada {@link #HEARTBEAT_MS} ms (socket PUB) y escucha el del otro
 * (socket SUB). Solo el GT en estado {@code ACTIVE} atiende clientes; el {@code PASSIVE} ignora sus
 * solicitudes, el cliente agota su tiempo de espera y reintenta en el otro GT (Lazy Pirate).</p>
 *
 * <pre>
 *  Estado     Evento                          Nuevo estado
 *  PRIMARY    el par esta en BACKUP           ACTIVE
 *  PRIMARY    el par esta en ACTIVE           PASSIVE   (el respaldo ya tomo el control)
 *  PRIMARY    solicitud y el par no responde  ACTIVE    (arranco solo)
 *  BACKUP     el par esta en ACTIVE           PASSIVE
 *  PASSIVE    el par esta en PRIMARY/BACKUP   ACTIVE    (el par se reinicio: se queda pasivo)
 *  PASSIVE    solicitud y sin heartbeat 2 s   ACTIVE    (FAILOVER)
 *  ACTIVE     el par tambien esta ACTIVE      el respaldo pasa a PASSIVE (evita dos activos)
 *  PASSIVE    el par tambien esta PASSIVE     el primario pasa a ACTIVE
 * </pre>
 *
 * <p>Regla clave: el pasivo toma el control solo si (1) dejo de recibir heartbeats durante
 * 2 × HEARTBEAT y (2) le llega una solicitud de un cliente. Si lo unico que falla es el enlace
 * entre los dos GT, los clientes siguen hablando con el activo y el pasivo no toma el control.</p>
 */
public class BinaryStar {

    public static final int HEARTBEAT_MS = 1000;

    public enum Estado {
        PRIMARY, BACKUP, ACTIVE, PASSIVE;

        /** Numero que viaja en el heartbeat. */
        public int codigo() {
            return ordinal() + 1;
        }

        public static Estado deCodigo(int c) {
            return values()[c - 1];
        }
    }

    private final boolean primario;
    private Estado estado;
    private long parVenceMs;

    public BinaryStar(boolean primario, long ahora) {
        this.primario = primario;
        this.estado = primario ? Estado.PRIMARY : Estado.BACKUP;
        this.parVenceMs = ahora + 2L * HEARTBEAT_MS;
    }

    public Estado estado() {
        return estado;
    }

    /** Llego un heartbeat del par con su estado. */
    public void heartbeatDelPar(Estado par, long ahora) {
        parVenceMs = ahora + 2L * HEARTBEAT_MS;
        Estado antes = estado;
        switch (estado) {
            case PRIMARY:
                if (par == Estado.BACKUP) {
                    estado = Estado.ACTIVE;
                } else if (par == Estado.ACTIVE) {
                    estado = Estado.PASSIVE;
                }
                break;
            case BACKUP:
                if (par == Estado.ACTIVE) {
                    estado = Estado.PASSIVE;
                }
                break;
            case ACTIVE:
                if (par == Estado.ACTIVE && !primario) {
                    estado = Estado.PASSIVE;          // dos activos: el respaldo cede
                }
                break;
            case PASSIVE:
                if (par == Estado.PRIMARY || par == Estado.BACKUP) {
                    estado = Estado.ACTIVE;           // el par se reinicio y se quedara pasivo
                } else if (par == Estado.PASSIVE && primario) {
                    estado = Estado.ACTIVE;           // dos pasivos: el primario toma el control
                }
                break;
            default:
                break;
        }
        avisar(antes, "heartbeat del par en " + par);
    }

    /**
     * Llego una solicitud de un cliente. Devuelve true si este GT debe atenderla (y puede hacer
     * failover si el par dejo de enviar heartbeats).
     */
    public boolean atenderCliente(long ahora) {
        Estado antes = estado;
        boolean parCaido = ahora >= parVenceMs;
        if ((estado == Estado.PRIMARY || estado == Estado.PASSIVE) && parCaido) {
            estado = Estado.ACTIVE;
        }
        avisar(antes, estado == Estado.ACTIVE && antes == Estado.PASSIVE
                ? "FAILOVER: el par no envia heartbeats hace mas de " + (2 * HEARTBEAT_MS) + " ms"
                : "solicitud de cliente y el par no responde");
        return estado == Estado.ACTIVE;
    }

    private void avisar(Estado antes, String motivo) {
        if (antes != estado) {
            System.out.println("[GT " + (primario ? "primario" : "respaldo") + "] " + antes + " -> " + estado
                    + " (" + motivo + ")");
        }
    }
}
