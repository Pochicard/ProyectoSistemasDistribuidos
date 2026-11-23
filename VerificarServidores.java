import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verificacion de los servidores del SRB (segunda entrega). No forma parte del sistema: es una
 * herramienta para comprobar que todo funciona en las 3 maquinas.
 *
 * Uso (en Windows, desde la carpeta del proyecto, con Java 17 o superior):
 *   java -cp target\srb.jar VerificarServidores.java funcional --gt=192.168.56.101:5555,192.168.56.101:5565 --ip=192.168.56.1
 *   java -cp target\srb.jar VerificarServidores.java fallas    --gt=192.168.56.101:5555,192.168.56.101:5565 --segundos=60
 */
public class VerificarServidores {

    static String[] gts;
    static int timeoutMs = 3000;
    static int pasaron = 0;
    static int fallaron = 0;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso: VerificarServidores funcional|fallas --gt=host:puerto[,host:puerto] [--ip=IP_DE_ESTE_PC] [--puerto=6001] [--segundos=60]");
            return;
        }
        String modo = args[0];
        String gt = "localhost:5555";
        String ip = "127.0.0.1";
        int puerto = 6001;
        int segundos = 60;
        for (String a : args) {
            if (a.startsWith("--gt=")) gt = a.substring(5);
            if (a.startsWith("--ip=")) ip = a.substring(5);
            if (a.startsWith("--puerto=")) puerto = Integer.parseInt(a.substring(9));
            if (a.startsWith("--segundos=")) segundos = Integer.parseInt(a.substring(11));
        }
        List<String> l = new ArrayList<>();
        for (String g : gt.split(",")) l.add("tcp://" + g.trim());
        gts = l.toArray(new String[0]);

        if (modo.equals("funcional")) {
            funcional(ip, puerto);
        } else {
            fallas(segundos);
        }
    }

    /** Cliente con reintento y cambio de GT (Lazy Pirate), como debe hacerlo el cliente final. */
    static final class Cliente implements AutoCloseable {
        final ZContext ctx = new ZContext();
        ZMQ.Socket s;
        int actual = 0;
        int cambiosDeGt = 0;

        Cliente() { s = nuevo(); }

        ZMQ.Socket nuevo() {
            ZMQ.Socket x = ctx.createSocket(SocketType.REQ);
            x.setLinger(0);
            x.setReceiveTimeOut(timeoutMs);
            x.connect(gts[actual]);
            return x;
        }

        String enviar(String m) {
            for (int intento = 0; intento < 2 * gts.length + 2; intento++) {
                s.send(m);
                String r = s.recvStr();
                if (r != null) return r;
                s.close();
                actual = (actual + 1) % gts.length;
                cambiosDeGt++;
                s = nuevo();
            }
            return null;
        }

        @Override
        public void close() { ctx.close(); }
    }

    static String id() { return UUID.randomUUID().toString(); }

    static void comprobar(boolean ok, String que, String detalle) {
        if (ok) pasaron++; else fallaron++;
        System.out.println((ok ? "  [OK]    " : "  [FALLA] ") + que + (detalle == null ? "" : "   (" + detalle + ")"));
    }

    /** Asientos libres de una ocurrencia, leidos del JSON de CONSULTA. */
    static int libres(Cliente c, String evento, int ocurrencia) {
        String json = c.enviar("CONSULTA:" + id());
        String clave = "\"" + evento + "\":{";
        int i = json.indexOf(clave);
        String campo = "\"asientosDisponibles" + ocurrencia + "\":";
        int j = json.indexOf(campo, i) + campo.length();
        int k = j;
        while (Character.isDigit(json.charAt(k))) k++;
        return Integer.parseInt(json.substring(j, k));
    }

    static void funcional(String ip, int puerto) throws Exception {
        BlockingQueue<String> notificaciones = new LinkedBlockingQueue<>();
        Thread receptor = new Thread(() -> {
            try (ZContext c = new ZContext()) {
                ZMQ.Socket pull = c.createSocket(SocketType.PULL);
                pull.bind("tcp://*:" + puerto);
                pull.setReceiveTimeOut(200);
                while (!Thread.currentThread().isInterrupted()) {
                    String n = pull.recvStr();
                    if (n != null) notificaciones.add(n);
                }
            }
        });
        receptor.setDaemon(true);
        receptor.start();

        System.out.println("Verificando contra " + String.join(", ", gts) + "\n");
        try (Cliente c = new Cliente()) {
            String evento = "E" + (21 + (int) (Math.random() * 30));   // evento de 1 ocurrencia, al azar

            System.out.println("1. Consulta");
            String json = c.enviar("CONSULTA:" + id());
            int eventos = json == null ? 0 : json.split("\"id\":").length - 1;
            comprobar(eventos == 50, "CONSULTA devuelve los 50 eventos", eventos + " eventos");
            String viejo = c.enviar("CONSULTA");
            comprobar(viejo != null && viejo.startsWith("{"), "CONSULTA con el formato de la 1.a entrega sigue funcionando", null);

            System.out.println("2. Reserva");
            int antes = libres(c, evento, 1);
            String idSol = id();
            String r1 = c.enviar("RESERVA:" + idSol + ":" + evento + ":1:2");
            comprobar(r1 != null && r1.startsWith("OK:"), "RESERVA nueva responde OK:<idReserva>", r1);
            String idReserva = r1 != null && r1.startsWith("OK:") ? r1.substring(3) : "0";
            comprobar(libres(c, evento, 1) == antes - 2, "Se descontaron 2 asientos de " + evento, antes + " -> " + libres(c, evento, 1));
            String viejaRes = c.enviar("RESERVA:" + evento + ":1:1");
            comprobar("OK".equals(viejaRes), "RESERVA con el formato de la 1.a entrega responde OK", viejaRes);

            System.out.println("3. Idempotencia (reintento con el mismo idSol)");
            int antesRepetir = libres(c, evento, 1);
            String r2 = c.enviar("RESERVA:" + idSol + ":" + evento + ":1:2");
            comprobar(r1 != null && r1.equals(r2), "El reintento devuelve la misma respuesta", r2);
            comprobar(libres(c, evento, 1) == antesRepetir, "El reintento NO descuenta asientos otra vez", null);

            System.out.println("4. Modificacion");
            int antesMod = libres(c, evento, 1);
            String m1 = c.enviar("MODIFICAR:" + id() + ":" + idReserva + ":1:5");
            comprobar("OK".equals(m1), "MODIFICAR de 2 a 5 asientos responde OK", m1);
            comprobar(libres(c, evento, 1) == antesMod - 3, "Se descontaron solo los 3 asientos de diferencia", null);
            int antesMod2 = libres(c, evento, 1);
            String m2 = c.enviar("MODIFICAR:" + id() + ":" + idReserva + ":1:1000");
            comprobar("RECHAZADO".equals(m2), "MODIFICAR a 1000 asientos (sin cupo) responde RECHAZADO", m2);
            comprobar(libres(c, evento, 1) == antesMod2, "El rechazo no cambia los asientos", null);
            String m3 = c.enviar("MODIFICAR:" + id() + ":" + idReserva + ":2:5");
            comprobar("RECHAZADO".equals(m3), "MODIFICAR a una fecha que el evento no tiene responde RECHAZADO", m3);

            System.out.println("5. Cancelacion asincrona y notificacion");
            int antesCan = libres(c, evento, 1);
            long t0 = System.nanoTime();
            String c1 = c.enviar("CANCELAR:" + id() + ":" + idReserva + ":" + ip + ":" + puerto);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            comprobar("ACEPTADA".equals(c1), "CANCELAR responde ACEPTADA", c1);
            comprobar(ms < 1000, "La respuesta llega de inmediato, sin esperar a la Persistencia", ms + " ms");
            String n1 = notificaciones.poll(8, TimeUnit.SECONDS);
            comprobar(("NOTIF:" + idReserva + ":CANCELADA").equals(n1), "Llega la notificacion de cancelacion", n1 == null
                    ? "no llego: revise el firewall de Windows y que --ip sea la IP de este PC" : n1);
            Thread.sleep(300);
            comprobar(libres(c, evento, 1) == antesCan + 5, "Se devolvieron los 5 asientos", null);
            c.enviar("CANCELAR:" + id() + ":" + idReserva + ":" + ip + ":" + puerto);
            String n2 = notificaciones.poll(8, TimeUnit.SECONDS);
            comprobar(("NOTIF:" + idReserva + ":RECHAZADA").equals(n2), "Cancelar dos veces la misma reserva notifica RECHAZADA", n2);

            System.out.println("6. Errores");
            String e1 = c.enviar("BORRAR:x");
            comprobar(e1 != null && e1.startsWith("ERROR"), "Una operacion desconocida responde ERROR", e1);

            if (c.cambiosDeGt > 0) {
                System.out.println("\n  Nota: el cliente cambio de GT " + c.cambiosDeGt + " vez/veces.");
            }
        }
        System.out.println("\nResultado: " + pasaron + " correctas, " + fallaron + " con falla.");
        System.exit(fallaron == 0 ? 0 : 1);
    }

    /** Carga continua para matar procesos mientras corre y ver que no se pierden solicitudes. */
    static void fallas(int segundos) throws Exception {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errores = new AtomicInteger();
        AtomicInteger cambios = new AtomicInteger();
        long fin = System.currentTimeMillis() + segundos * 1000L;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        System.out.println("Enviando consultas y reservas con 6 clientes durante " + segundos + " s.");
        System.out.println("Mientras corre, haga Ctrl + C en el GT primario o en un RMC y observe.\n");
        for (int h = 0; h < 6; h++) {
            pool.submit(() -> {
                try (Cliente c = new Cliente()) {
                    int k = 0;
                    while (System.currentTimeMillis() < fin) {
                        String m = (k++ % 3 == 0) ? "RESERVA:" + id() + ":E" + (1 + (int) (Math.random() * 50)) + ":1:1"
                                : "CONSULTA:" + id();
                        String r = c.enviar(m);
                        if (r == null || r.startsWith("ERROR")) errores.incrementAndGet(); else ok.incrementAndGet();
                        Thread.sleep(50);
                    }
                    cambios.addAndGet(c.cambiosDeGt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }
        while (System.currentTimeMillis() < fin) {
            Thread.sleep(5000);
            System.out.println("  respondidas=" + ok + "  errores o sin respuesta=" + errores);
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        System.out.println("\nResultado: " + ok + " respondidas, " + errores + " errores, "
                + cambios + " reintentos con cambio de GT.");
        System.out.println(errores.get() == 0 ? "[OK] Ninguna solicitud se perdio." : "[FALLA] Hubo solicitudes sin respuesta.");
    }
}
