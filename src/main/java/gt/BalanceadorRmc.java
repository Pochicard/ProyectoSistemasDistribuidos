package gt;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Reparte las solicitudes sincronas del GT entre varias replicas del RMC y tolera la caida de una.
 *
 * <h2>Como funciona</h2>
 * <ul>
 *   <li>Por cada RMC hay un socket {@code DEALER} conectado a su {@code REP}. DEALER es asincrono:
 *       el GT puede tener varias solicitudes en curso en distintos RMC a la vez (escalabilidad).
 *       A un REP se le envia la trama vacia delimitadora seguida del mensaje.</li>
 *   <li>Cada solicitud va al RMC disponible con menos solicitudes en curso.</li>
 *   <li>Un REP responde en el mismo orden en que recibe, asi que por cada RMC se guarda una cola
 *       FIFO de solicitudes en curso; cada respuesta corresponde a la primera de su cola.</li>
 *   <li><b>RMC apagado:</b> el DEALER no encola hacia conexiones caidas, asi que el envio falla de
 *       inmediato; el RMC queda en "enfriamiento" unos segundos y se usa otro.</li>
 *   <li><b>RMC que no responde:</b> si la solicitud mas antigua de un RMC lleva mas de
 *       {@code timeoutMs} sin respuesta y HAY OTRO RMC disponible, se descarta su socket
 *       ({@code linger=0}), se crea uno nuevo, se enfria y sus solicitudes pendientes se reenvian
 *       al otro. Si es el UNICO disponible, no se descarta: se sigue esperando, porque puede estar
 *       solo lento (descartarlo dejaria al GT sin ningun RMC). Reenviar es seguro porque la
 *       Persistencia ignora un {@code idSol} repetido.</li>
 *   <li>Si todos los RMC estan en enfriamiento, se intenta igual con ellos antes de rendirse; una
 *       solicitud solo se responde con {@code ERROR} si espera mas de {@code 3 × timeoutMs}.</li>
 * </ul>
 * Esta clase no es segura entre hilos: la usa solo el hilo principal del GT.
 */
public class BalanceadorRmc {

    /** Una solicitud de un cliente que espera respuesta de un RMC. */
    public record Pendiente(byte[] cliente, String mensaje, long recibidaMs) {
    }

    /** Respuesta lista para devolver al cliente. */
    public record Respuesta(byte[] cliente, String mensaje, String respuesta) {
    }

    private static final class Rmc {
        final String endpoint;
        ZMQ.Socket socket;
        final Deque<Pendiente> enCurso = new ArrayDeque<>();
        final Deque<Long> enviadas = new ArrayDeque<>();   // hora de envio de cada una, mismo orden
        long enfriamientoHastaMs;
        boolean avisadoLento;

        Rmc(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    private final ZContext context;
    private final List<Rmc> rmcs = new ArrayList<>();
    private final Deque<Pendiente> enEspera = new ArrayDeque<>();
    private final int timeoutMs;
    private final int enfriamientoMs;
    private int turno;

    public BalanceadorRmc(ZContext context, List<String> endpoints, int timeoutMs, int enfriamientoMs) {
        this.context = context;
        this.timeoutMs = timeoutMs;
        this.enfriamientoMs = enfriamientoMs;
        for (String e : endpoints) {
            Rmc r = new Rmc(e);
            r.socket = crearSocket(e);
            rmcs.add(r);
        }
    }

    private ZMQ.Socket crearSocket(String endpoint) {
        ZMQ.Socket s = context.createSocket(SocketType.DEALER);
        s.setLinger(0);
        // En JeroMQ 0.5.4 el valor esta invertido respecto a libzmq: false = solo encolar hacia
        // conexiones ya establecidas. Asi, si el RMC esta apagado, send() falla en vez de encolar.
        s.setImmediate(false);
        s.connect(endpoint);
        return s;
    }

    /** Sockets que el GT debe vigilar en su poll (cambian si un RMC se reinicia). */
    public List<ZMQ.Socket> sockets() {
        List<ZMQ.Socket> l = new ArrayList<>();
        for (Rmc r : rmcs) {
            l.add(r.socket);
        }
        return l;
    }

    public int cantidad() {
        return rmcs.size();
    }

    /** Envia una solicitud a un RMC; si ninguno la acepta ahora, la deja en espera. */
    public void enviar(Pendiente p, long ahora) {
        if (!intentarEnviar(p, ahora)) {
            enEspera.addLast(p);
        }
    }

    /** El RMC con menos trabajo en curso (empates por turnos), o null. */
    private Rmc elegir(long ahora, boolean incluirEnfriados, List<Rmc> descartados) {
        Rmc elegido = null;
        int n = rmcs.size();
        for (int k = 0; k < n; k++) {
            Rmc r = rmcs.get((turno + k) % n);
            if (descartados.contains(r) || (!incluirEnfriados && r.enfriamientoHastaMs > ahora)) {
                continue;
            }
            if (elegido == null || r.enCurso.size() < elegido.enCurso.size()) {
                elegido = r;
            }
        }
        return elegido;
    }

    private boolean intentarEnviar(Pendiente p, long ahora) {
        List<Rmc> descartados = new ArrayList<>();
        for (boolean incluirEnfriados : new boolean[] {false, true}) {
            Rmc r;
            while ((r = elegir(ahora, incluirEnfriados, descartados)) != null) {
                boolean ok = r.socket.send(new byte[0], ZMQ.SNDMORE | ZMQ.DONTWAIT)
                        && r.socket.send(p.mensaje(), ZMQ.DONTWAIT);
                if (ok) {
                    r.enCurso.addLast(p);
                    r.enviadas.addLast(ahora);
                    turno = (rmcs.indexOf(r) + 1) % rmcs.size();
                    return true;
                }
                // No conectado (RMC apagado): enfriarlo, guardar lo que tenia y probar con otro.
                if (r.enfriamientoHastaMs <= ahora) {
                    System.out.println("[GT] RMC " + r.endpoint + " no esta conectado; se prueba otro");
                }
                enEspera.addAll(reiniciar(r, ahora));
                descartados.add(r);
            }
        }
        return false;
    }

    /** true si el socket del RMC {@code i} tiene un mensaje listo para leer. */
    public boolean tieneMensaje(int i) {
        return (rmcs.get(i).socket.getEvents() & ZMQ.Poller.POLLIN) != 0;
    }

    /** Lee una respuesta del RMC cuyo socket esta en la posicion {@code i}; null si no era valida. */
    public Respuesta recibir(int i) {
        Rmc r = rmcs.get(i);
        byte[] vacio = r.socket.recv(ZMQ.DONTWAIT);
        if (vacio == null) {
            return null;
        }
        String respuesta = r.socket.hasReceiveMore() ? r.socket.recvStr() : new String(vacio, ZMQ.CHARSET);
        Pendiente p = r.enCurso.pollFirst();
        r.enviadas.pollFirst();
        if (r.avisadoLento) {
            System.out.println("[GT] RMC " + r.endpoint + " volvio a responder");
            r.avisadoLento = false;
        }
        if (p == null) {
            return null;   // respuesta tardia de algo ya reenviado
        }
        return new Respuesta(p.cliente(), p.mensaje(), respuesta);
    }

    /**
     * Revisa tiempos: reasigna el trabajo de los RMC que no responden (si hay otro disponible),
     * intenta enviar lo que estaba en espera y devuelve las solicitudes que ya no se pueden atender
     * (para responder ERROR).
     */
    public List<Pendiente> revisar(long ahora) {
        for (Rmc r : rmcs) {
            Long primera = r.enviadas.peekFirst();
            if (primera == null || ahora - primera <= timeoutMs) {
                continue;
            }
            boolean hayOtro = false;
            for (Rmc o : rmcs) {
                hayOtro |= o != r && o.enfriamientoHastaMs <= ahora;
            }
            if (!hayOtro) {
                if (!r.avisadoLento) {
                    System.out.println("[GT] RMC " + r.endpoint + " tarda mas de " + timeoutMs
                            + " ms, pero es el unico disponible: se sigue esperando");
                    r.avisadoLento = true;
                }
                continue;
            }
            List<Pendiente> huerfanas = reiniciar(r, ahora);
            System.out.println("[GT] RMC " + r.endpoint + " no responde hace " + (ahora - primera)
                    + " ms: se da por caido y se reenvian " + huerfanas.size() + " solicitudes a otro RMC");
            for (Pendiente p : huerfanas) {
                enviar(p, ahora);
            }
        }
        List<Pendiente> vencidas = new ArrayList<>();
        int enCola = enEspera.size();
        Iterator<Pendiente> it = enEspera.iterator();
        for (int k = 0; k < enCola && it.hasNext(); k++) {
            Pendiente p = it.next();
            if (ahora - p.recibidaMs() > 3L * timeoutMs) {
                it.remove();
                vencidas.add(p);
            }
        }
        List<Pendiente> reintentar = new ArrayList<>(enEspera);
        enEspera.clear();
        for (Pendiente p : reintentar) {
            enviar(p, ahora);
        }
        return vencidas;
    }

    /** Descarta el socket del RMC, crea uno nuevo, lo enfria y devuelve lo que tenia en curso. */
    private List<Pendiente> reiniciar(Rmc r, long ahora) {
        List<Pendiente> huerfanas = new ArrayList<>(r.enCurso);
        context.destroySocket(r.socket);
        r.socket = crearSocket(r.endpoint);
        r.enCurso.clear();
        r.enviadas.clear();
        r.enfriamientoHastaMs = ahora + enfriamientoMs;
        r.avisadoLento = false;
        return huerfanas;
    }
}
