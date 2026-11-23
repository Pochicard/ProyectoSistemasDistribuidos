package cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Cliente interactivo de consola del SRB.
 *
 * <p>Consulta, reserva y modificación son síncronas (REQ/REP). La cancelación es asíncrona: el GT responde
 * ACEPTADA y el resultado llega después al {@link ReceptorNotificaciones}. Cada operación lleva un idSol que
 * se repite en sus reintentos, por lo que reintentar en el GT de respaldo no duplica operaciones.</p>
 */
public class ClienteMain {

    private static final String GT_DEFECTO = "localhost:5555,localhost:5565";
    private static final String IP_DEFECTO = "127.0.0.1";
    private static final int PUERTO_NOTIF_DEFECTO = 6001;
    private static final int TIMEOUT_DEFECTO_MS = 3000;
    private static final int REINTENTOS_DEFECTO = 2;

    /** Estado de una reserva hecha en esta sesión. */
    enum EstadoReserva { ACTIVA, CANCELACION_EN_CURSO, CANCELADA, CANCELACION_RECHAZADA }

    /** Reserva hecha en esta sesión. El estado lo actualiza también el hilo de notificaciones. */
    static final class ReservaLocal {
        final String evento;
        volatile int ocurrencia;
        volatile int cantidad;
        volatile EstadoReserva estado = EstadoReserva.ACTIVA;

        ReservaLocal(String evento, int ocurrencia, int cantidad) {
            this.evento = evento;
            this.ocurrencia = ocurrencia;
            this.cantidad = cantidad;
        }
    }

    private final ConexionGt conexion;
    private final BufferedReader entrada;
    private final int intentos;
    private final String ipCliente;
    private final int puertoNotificaciones;
    private final boolean recibeNotificaciones;
    /** Compartido con el hilo de notificaciones; ordenado por número de reserva. */
    private final Map<Long, ReservaLocal> reservas = new ConcurrentSkipListMap<>();

    private static class FinDeEntrada extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FinDeEntrada() {
            super(null, null, false, false);
        }
    }

    ClienteMain(ConexionGt conexion, BufferedReader entrada, int reintentos, String ipCliente,
                int puertoNotificaciones, boolean recibeNotificaciones) {
        this.conexion = conexion;
        this.entrada = entrada;
        this.intentos = 1 + reintentos;
        this.ipCliente = ipCliente;
        this.puertoNotificaciones = puertoNotificaciones;
        this.recibeNotificaciones = recibeNotificaciones;
    }

    public static void main(String[] args) {
        List<String> gts;
        String ip = IP_DEFECTO;
        int puertoNotif = PUERTO_NOTIF_DEFECTO;
        int timeoutMs = TIMEOUT_DEFECTO_MS;
        int reintentos = REINTENTOS_DEFECTO;

        try {
            String gtTexto = GT_DEFECTO;
            for (String arg : args) {
                if (arg.equals("--ayuda") || arg.equals("-h") || arg.equals("--help")) {
                    imprimirAyuda();
                    return;
                } else if (arg.startsWith("--gt=")) {
                    gtTexto = arg.substring("--gt=".length());
                } else if (arg.startsWith("--ip=")) {
                    ip = arg.substring("--ip=".length()).trim();
                    if (ip.isEmpty() || ip.contains(":")) {
                        throw new IllegalArgumentException("--ip debe ser una dirección IPv4 o un nombre de equipo");
                    }
                } else if (arg.startsWith("--puerto-notif=")) {
                    puertoNotif = enteroEnRango(arg.substring("--puerto-notif=".length()), 1, 65535, "puerto-notif");
                } else if (arg.startsWith("--timeout=")) {
                    timeoutMs = enteroEnRango(arg.substring("--timeout=".length()), 100, 600_000, "timeout");
                } else if (arg.startsWith("--reintentos=")) {
                    reintentos = enteroEnRango(arg.substring("--reintentos=".length()), 0, 10, "reintentos");
                } else {
                    throw new IllegalArgumentException("Argumento no reconocido: " + arg);
                }
            }
            gts = ConexionGt.parsearDirecciones(gtTexto);
        } catch (IllegalArgumentException e) {
            System.out.println("Error en los argumentos: " + e.getMessage());
            imprimirAyuda();
            System.exit(2);
            return;
        }

        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));
        ReceptorNotificaciones receptor = null;
        ClienteMain[] cliente = new ClienteMain[1];
        try {
            receptor = new ReceptorNotificaciones(puertoNotif, (n, texto) -> {
                if (cliente[0] != null) {
                    cliente[0].procesarNotificacion(n, texto);
                }
            });
        } catch (IllegalStateException e) {
            System.out.println("AVISO: " + e.getMessage());
            System.out.println("Las notificaciones de cancelación no llegarán. Use otro --puerto-notif.");
        }

        try (ConexionGt conexion = new ConexionGt(gts, timeoutMs)) {
            cliente[0] = new ClienteMain(conexion, teclado, reintentos, ip, puertoNotif, receptor != null);
            cliente[0].ejecutar();
        } finally {
            if (receptor != null) {
                receptor.close();
            }
        }
        System.out.println("Cliente finalizado. Sockets y contextos ZeroMQ cerrados.");
    }

    private static int enteroEnRango(String texto, int min, int max, String nombre) {
        try {
            int valor = Integer.parseInt(texto.trim());
            if (valor < min || valor > max) {
                throw new IllegalArgumentException(nombre + " debe estar entre " + min + " y " + max);
            }
            return valor;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(nombre + " debe ser un número entero: " + texto);
        }
    }

    private static void imprimirAyuda() {
        System.out.println("Uso: cliente.ClienteMain [--gt=host:puerto[,host:puerto]] [--ip=IP] [--puerto-notif=6001]");
        System.out.println("                         [--timeout=3000] [--reintentos=2]");
        System.out.println("  --gt            GT primario y de respaldo (defecto " + GT_DEFECTO + ")");
        System.out.println("  --ip            IP de este equipo a la que llegan las notificaciones (defecto " + IP_DEFECTO + ")");
        System.out.println("  --puerto-notif  puerto local para recibir notificaciones (defecto " + PUERTO_NOTIF_DEFECTO + ")");
        System.out.println("  --timeout       espera máxima por respuesta en ms (defecto " + TIMEOUT_DEFECTO_MS + ")");
        System.out.println("  --reintentos    reintentos por operación, alternando GT (defecto " + REINTENTOS_DEFECTO + ")");
    }

    void ejecutar() {
        System.out.println("==========================================");
        System.out.println("[Cliente SRB] Gestores de Transacciones: " + String.join(", ", conexion.getEndpoints()));
        System.out.println("[Cliente SRB] Tiempo límite " + conexion.getTimeoutMs() + " ms, " + intentos
                + " intento(s) por operación");
        System.out.println("[Cliente SRB] Notificaciones en tcp://" + ipCliente + ":" + puertoNotificaciones
                + (recibeNotificaciones ? "" : " (NO disponible)"));
        System.out.println("==========================================");
        try {
            boolean continuar = true;
            while (continuar) {
                System.out.println();
                System.out.println("----- MENÚ -----");
                System.out.println("1. Consultar todos los eventos");
                System.out.println("2. Reservar asientos");
                System.out.println("3. Ver disponibilidad de un evento");
                System.out.println("4. Mis reservas");
                System.out.println("5. Modificar una reserva");
                System.out.println("6. Cancelar una reserva");
                System.out.println("0. Salir");
                String opcion = leer("Opción: ").trim();
                switch (opcion) {
                    case "1":
                        opcionConsultar();
                        break;
                    case "2":
                        opcionReservar();
                        break;
                    case "3":
                        opcionVerEvento();
                        break;
                    case "4":
                        imprimirReservas();
                        break;
                    case "5":
                        opcionModificar();
                        break;
                    case "6":
                        opcionCancelar();
                        break;
                    case "0":
                        continuar = false;
                        break;
                    default:
                        System.out.println("Opción no válida. Escriba un número del 0 al 6.");
                }
            }
        } catch (FinDeEntrada e) {
            System.out.println();
            System.out.println("Entrada cerrada.");
        }
    }

    private void opcionConsultar() {
        Map<String, EventoDto> eventos = consultar();
        if (eventos != null) {
            imprimirTabla(eventos);
        }
    }

    private void opcionVerEvento() {
        Map<String, EventoDto> eventos = consultar();
        if (eventos == null) {
            return;
        }
        String id = pedirIdEvento(eventos);
        if (id != null) {
            imprimirEvento(eventos.get(id));
        }
    }

    private void opcionReservar() {
        // Catálogo actualizado para validar la selección del usuario.
        Map<String, EventoDto> eventos = consultar();
        if (eventos == null) {
            System.out.println("No se puede reservar sin consultar primero el catálogo.");
            return;
        }
        String id = pedirIdEvento(eventos);
        if (id == null) {
            return;
        }
        EventoDto evento = eventos.get(id);
        imprimirEvento(evento);

        int ocurrencia;
        if (evento.getOcurrencias() == 1) {
            ocurrencia = 1;
            System.out.println("El evento tiene una sola ocurrencia; se usará la ocurrencia 1.");
        } else {
            Integer elegida = pedirOcurrencia(evento, null);
            if (elegida == null) {
                return;
            }
            ocurrencia = elegida;
        }

        Integer cantidad = pedirCantidad(null);
        if (cantidad == null) {
            return;
        }
        int disponiblesAntes = evento.getDisponibles(ocurrencia);
        if (cantidad > disponiblesAntes) {
            System.out.println("Aviso: según la última consulta solo hay " + disponiblesAntes
                    + " asientos. El servidor probablemente rechazará la solicitud.");
        }
        if (!confirmar("¿Reservar " + cantidad + " asiento(s) en " + id + ", ocurrencia " + ocurrencia
                + " (" + evento.getFecha(ocurrencia) + ")? (s/n): ")) {
            System.out.println("Reserva cancelada por el usuario; no se envió nada.");
            return;
        }

        String idSol = ProtocoloSrb.nuevoIdSolicitud();
        String mensaje = ProtocoloSrb.mensajeReserva(idSol, id, ocurrencia, cantidad);
        String respuesta = enviar("RESERVA", mensaje);
        if (respuesta == null) {
            return;
        }
        try {
            if (ResultadoReserva.desde(respuesta) == ResultadoReserva.EXITOSA) {
                long idReserva = ProtocoloSrb.idReserva(respuesta);
                System.out.println("RESERVA EXITOSA. Número de reserva: " + idReserva);
                if (idReserva > 0) {
                    reservas.put(idReserva, new ReservaLocal(id, ocurrencia, cantidad));
                }
                mostrarCambio(id, ocurrencia, disponiblesAntes, cantidad);
            } else {
                System.out.println("RESERVA RECHAZADA: no hay suficientes asientos disponibles en esa ocurrencia.");
            }
        } catch (ErrorComunicacionException e) {
            System.out.println("RESPUESTA INVÁLIDA: " + e.getMessage());
        }
    }

    private void opcionModificar() {
        imprimirReservas();
        Long idReserva = pedirIdReserva();
        if (idReserva == null) {
            return;
        }
        ReservaLocal local = reservas.get(idReserva);
        if (local != null && local.estado != EstadoReserva.ACTIVA) {
            System.out.println("La reserva " + idReserva + " no está activa (" + local.estado + ").");
            return;
        }

        int ocurrenciaNueva;
        int cantidadNueva;
        if (local != null) {
            Map<String, EventoDto> eventos = consultar();
            EventoDto evento = eventos == null ? null : eventos.get(local.evento);
            if (evento == null) {
                System.out.println("No se pudo obtener la información del evento " + local.evento + ".");
                return;
            }
            System.out.println("Reserva actual: " + local.cantidad + " asiento(s) en " + local.evento
                    + ", ocurrencia " + local.ocurrencia + " (" + evento.getFecha(local.ocurrencia) + ").");
            imprimirEvento(evento);
            if (evento.getOcurrencias() == 1) {
                ocurrenciaNueva = 1;
                System.out.println("El evento tiene una sola fecha; solo se puede cambiar la cantidad.");
            } else {
                Integer o = pedirOcurrencia(evento, local.ocurrencia);
                if (o == null) {
                    return;
                }
                ocurrenciaNueva = o;
            }
            Integer c = pedirCantidad(local.cantidad);
            if (c == null) {
                return;
            }
            cantidadNueva = c;
            if (ocurrenciaNueva == local.ocurrencia && cantidadNueva == local.cantidad) {
                System.out.println("No hay cambios; no se envió nada.");
                return;
            }
        } else {
            // Reserva de otra sesión: el servidor valida la ocurrencia contra el evento real.
            System.out.println("La reserva " + idReserva + " no es de esta sesión; el servidor validará los datos.");
            Integer o = pedirEnteroPositivo("Nueva ocurrencia (1 o 2, vacío para cancelar): ");
            if (o == null) {
                return;
            }
            Integer c = pedirCantidad(null);
            if (c == null) {
                return;
            }
            ocurrenciaNueva = o;
            cantidadNueva = c;
        }

        if (!confirmar("¿Modificar la reserva " + idReserva + " a " + cantidadNueva + " asiento(s) en la ocurrencia "
                + ocurrenciaNueva + "? (s/n): ")) {
            System.out.println("Modificación cancelada por el usuario; no se envió nada.");
            return;
        }
        String idSol = ProtocoloSrb.nuevoIdSolicitud();
        String respuesta = enviar("MODIFICAR",
                ProtocoloSrb.mensajeModificar(idSol, idReserva, ocurrenciaNueva, cantidadNueva));
        if (respuesta == null) {
            return;
        }
        try {
            if (ResultadoReserva.desde(respuesta) == ResultadoReserva.EXITOSA) {
                System.out.println("MODIFICACIÓN EXITOSA.");
                if (local != null) {
                    local.ocurrencia = ocurrenciaNueva;
                    local.cantidad = cantidadNueva;
                }
            } else {
                System.out.println("MODIFICACIÓN RECHAZADA: no hay cupo para el cambio, o la reserva no existe"
                        + " o no está activa. La reserva no cambió.");
            }
        } catch (ErrorComunicacionException e) {
            System.out.println("RESPUESTA INVÁLIDA: " + e.getMessage());
        }
    }

    private void opcionCancelar() {
        imprimirReservas();
        Long idReserva = pedirIdReserva();
        if (idReserva == null) {
            return;
        }
        ReservaLocal local = reservas.get(idReserva);
        if (local != null && local.estado != EstadoReserva.ACTIVA) {
            System.out.println("La reserva " + idReserva + " no está activa (" + local.estado + ").");
            return;
        }
        if (!recibeNotificaciones) {
            System.out.println("Aviso: el receptor de notificaciones no está activo; no verá el resultado.");
        }
        if (!confirmar("¿Cancelar la reserva " + idReserva + "? (s/n): ")) {
            System.out.println("Cancelación descartada por el usuario; no se envió nada.");
            return;
        }
        String idSol = ProtocoloSrb.nuevoIdSolicitud();
        String respuesta = enviar("CANCELAR",
                ProtocoloSrb.mensajeCancelar(idSol, idReserva, ipCliente, puertoNotificaciones));
        if (respuesta == null) {
            return;
        }
        if (ProtocoloSrb.RESPUESTA_ACEPTADA.equals(respuesta)) {
            System.out.println("CANCELACIÓN ACEPTADA. El resultado llegará como notificación.");
            if (local != null) {
                local.estado = EstadoReserva.CANCELACION_EN_CURSO;
            }
        } else {
            System.out.println("El servidor no aceptó la cancelación: " + respuesta);
        }
    }

    /** Ejecutado en el hilo receptor cuando llega una notificación. */
    void procesarNotificacion(ProtocoloSrb.Notificacion n, String texto) {
        if (n == null) {
            System.out.println(System.lineSeparator() + "[NOTIFICACIÓN] Mensaje no reconocido: " + texto);
            return;
        }
        ReservaLocal local = reservas.get(n.idReserva());
        if (local != null) {
            local.estado = n.cancelada() ? EstadoReserva.CANCELADA : EstadoReserva.CANCELACION_RECHAZADA;
        }
        System.out.println(System.lineSeparator() + "[NOTIFICACIÓN] Reserva " + n.idReserva()
                + (n.cancelada() ? " CANCELADA: los asientos fueron liberados."
                : ": la cancelación fue RECHAZADA (la reserva no existe o ya estaba cancelada)."));
        System.out.print("Opción: ");
        System.out.flush();
    }

    /** Consulta de nuevo para comprobar la disponibilidad después de una reserva exitosa. */
    private void mostrarCambio(String id, int ocurrencia, int antes, int cantidad) {
        Map<String, EventoDto> eventos = consultar();
        if (eventos == null || !eventos.containsKey(id)) {
            System.out.println("No se pudo consultar la disponibilidad actualizada.");
            return;
        }
        int despues = eventos.get(id).getDisponibles(ocurrencia);
        System.out.println("Disponibilidad de " + id + " (ocurrencia " + ocurrencia + "): "
                + antes + " -> " + despues + "  (esperado " + (antes - cantidad) + ")");
        if (despues != antes - cantidad) {
            System.out.println("Nota: la diferencia no coincide exactamente; otro cliente pudo reservar"
                    + " en el intervalo entre consultas.");
        }
    }

    // ------------------------------------------------------------------ comunicación

    /** CONSULTA con reintentos. Devuelve los eventos ordenados, o null si no fue posible. */
    private Map<String, EventoDto> consultar() {
        String respuesta = enviar("CONSULTA", ProtocoloSrb.mensajeConsulta(ProtocoloSrb.nuevoIdSolicitud()));
        if (respuesta == null) {
            return null;
        }
        try {
            return ProtocoloSrb.parsearConsulta(respuesta);
        } catch (ErrorComunicacionException e) {
            System.out.println("[CONSULTA] " + e.getMessage());
            return null;
        }
    }

    /**
     * Envía un mensaje con reintentos sobre los GT configurados. El mensaje conserva su idSol en todos
     * los intentos, de modo que un reintento nunca aplica dos veces la misma operación.
     * Devuelve null si todos los intentos fallaron.
     */
    private String enviar(String operacion, String mensaje) {
        long inicio = System.nanoTime();
        try {
            String respuesta = conexion.enviarConReintentos(mensaje, intentos,
                    aviso -> System.out.println("[" + operacion + "] " + aviso));
            System.out.printf("[%s] Respuesta de %s en %.2f ms%n", operacion, conexion.getEndpoint(),
                    (System.nanoTime() - inicio) / 1_000_000.0);
            return respuesta;
        } catch (ErrorComunicacionException e) {
            if (e.getTipo() == ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA) {
                System.out.println("[" + operacion + "] Respuesta inválida: " + e.getMessage());
            } else {
                System.out.println("[" + operacion + "] Ningún Gestor de Transacciones respondió.");
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ entrada del usuario

    /** Lee una línea; si la entrada se cerró, termina el cliente de forma ordenada. */
    private String leer(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            String linea = entrada.readLine();
            if (linea == null) {
                throw new FinDeEntrada();
            }
            return linea;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Devuelve un identificador válido, o null si el usuario deja la línea vacía. */
    private String pedirIdEvento(Map<String, EventoDto> eventos) {
        while (true) {
            String texto = leer("Identificador del evento (ej. E2, vacío para cancelar): ");
            if (texto.isBlank()) {
                return null;
            }
            try {
                return ValidadorEntrada.validarIdEvento(texto, eventos);
            } catch (IllegalArgumentException e) {
                System.out.println("  " + e.getMessage());
            }
        }
    }

    // Si el usuario deja la línea vacía se conserva "actual". Devuelve null si cancela.
    private Integer pedirOcurrencia(EventoDto evento, Integer actual) {
        while (true) {
            String texto = leer("Ocurrencia (1-" + evento.getOcurrencias()
                    + (actual == null ? ", vacío para cancelar): " : ", vacío para mantener " + actual + "): "));
            if (texto.isBlank()) {
                return actual;
            }
            try {
                return ValidadorEntrada.validarOcurrencia(texto, evento);
            } catch (IllegalArgumentException e) {
                System.out.println("  " + e.getMessage());
            }
        }
    }

    private Integer pedirCantidad(Integer actual) {
        while (true) {
            String texto = leer("Cantidad de asientos"
                    + (actual == null ? " (vacío para cancelar): " : " (vacío para mantener " + actual + "): "));
            if (texto.isBlank()) {
                return actual;
            }
            try {
                return ValidadorEntrada.validarCantidad(texto);
            } catch (IllegalArgumentException e) {
                System.out.println("  " + e.getMessage());
            }
        }
    }

    private Integer pedirEnteroPositivo(String prompt) {
        while (true) {
            String texto = leer(prompt);
            if (texto.isBlank()) {
                return null;
            }
            try {
                return ValidadorEntrada.validarCantidad(texto);
            } catch (IllegalArgumentException e) {
                System.out.println("  Debe ser un número entero mayor que cero.");
            }
        }
    }

    private Long pedirIdReserva() {
        while (true) {
            String texto = leer("Número de reserva (vacío para cancelar): ");
            if (texto.isBlank()) {
                return null;
            }
            try {
                return ValidadorEntrada.validarIdReserva(texto);
            } catch (IllegalArgumentException e) {
                System.out.println("  " + e.getMessage());
            }
        }
    }

    private boolean confirmar(String prompt) {
        while (true) {
            String r = leer(prompt).trim().toLowerCase();
            if (r.equals("s") || r.equals("si") || r.equals("sí")) {
                return true;
            }
            if (r.equals("n") || r.equals("no")) {
                return false;
            }
            System.out.println("  Responda s o n.");
        }
    }

    // ------------------------------------------------------------------ presentación

    private void imprimirReservas() {
        if (reservas.isEmpty()) {
            System.out.println("No hay reservas registradas en esta sesión.");
            return;
        }
        System.out.printf("%-8s %-7s %-6s %9s  %s%n", "Reserva", "Evento", "Ocur.", "Asientos", "Estado");
        for (Map.Entry<Long, ReservaLocal> e : reservas.entrySet()) {
            ReservaLocal r = e.getValue();
            System.out.printf("%-8d %-7s %-6d %9d  %s%n", e.getKey(), r.evento, r.ocurrencia, r.cantidad, r.estado);
        }
    }

    private static void imprimirTabla(Map<String, EventoDto> eventos) {
        System.out.println();
        System.out.printf("%-7s %-6s %-12s %11s%n", "Evento", "Ocur.", "Fecha", "Disponibles");
        System.out.println("------- ------ ------------ -----------");
        int ocurrencias = 0;
        long asientos = 0;
        for (EventoDto e : eventos.values()) {
            for (int o = 1; o <= e.getOcurrencias(); o++) {
                System.out.printf("%-7s %-6d %-12s %11d%n", e.getId(), o, e.getFecha(o), e.getDisponibles(o));
                ocurrencias++;
                asientos += e.getDisponibles(o);
            }
        }
        System.out.println("------- ------ ------------ -----------");
        System.out.println("Total: " + eventos.size() + " eventos, " + ocurrencias
                + " ocurrencias, " + asientos + " asientos disponibles.");
    }

    private static void imprimirEvento(EventoDto e) {
        System.out.println("Evento " + e.getId() + " (" + e.getOcurrencias()
                + (e.getOcurrencias() == 1 ? " ocurrencia)" : " ocurrencias)"));
        for (int o = 1; o <= e.getOcurrencias(); o++) {
            System.out.printf("  Ocurrencia %d | Fecha %s | Disponibles %d%n", o, e.getFecha(o), e.getDisponibles(o));
        }
    }
}
