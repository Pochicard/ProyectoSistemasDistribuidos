package cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Cliente interactivo de consola del Sistema de Reserva de Boletos (SRB).
 *
 * <p>Se comunica únicamente con el Gestor de Transacciones mediante ZeroMQ REQ/REP,
 * respetando el protocolo de texto existente (ver {@link ProtocoloSrb}).</p>
 *
 * <pre>
 * Uso:   java -cp &lt;classpath&gt; cliente.ClienteMain [--gt=host:puerto] [--timeout=ms] [--reintentos=n]
 * Valores por defecto:  --gt=localhost:5555  --timeout=5000  --reintentos=2
 * </pre>
 */
public class ClienteMain {

    private static final String HOST_DEFECTO = "localhost";
    private static final int PUERTO_DEFECTO = 5555;
    private static final int TIMEOUT_DEFECTO_MS = 5000;
    private static final int REINTENTOS_DEFECTO = 2;

    private final ConexionGt conexion;
    private final BufferedReader entrada;
    private final int reintentosConsulta;

    /** Se lanza cuando la entrada estándar se cierra (Ctrl+D / Ctrl+Z o fin de un archivo redirigido). */
    private static class FinDeEntrada extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FinDeEntrada() {
            super(null, null, false, false);
        }
    }

    ClienteMain(ConexionGt conexion, BufferedReader entrada, int reintentosConsulta) {
        this.conexion = conexion;
        this.entrada = entrada;
        this.reintentosConsulta = reintentosConsulta;
    }

    // ------------------------------------------------------------------ arranque

    public static void main(String[] args) {
        String host = HOST_DEFECTO;
        int puerto = PUERTO_DEFECTO;
        int timeoutMs = TIMEOUT_DEFECTO_MS;
        int reintentos = REINTENTOS_DEFECTO;

        try {
            for (String arg : args) {
                if (arg.equals("--ayuda") || arg.equals("-h") || arg.equals("--help")) {
                    imprimirAyuda();
                    return;
                } else if (arg.startsWith("--gt=")) {
                    String valor = arg.substring("--gt=".length());
                    int dosPuntos = valor.lastIndexOf(':');
                    if (dosPuntos <= 0 || dosPuntos == valor.length() - 1) {
                        throw new IllegalArgumentException("Use --gt=host:puerto, por ejemplo --gt=192.168.0.10:5555");
                    }
                    host = valor.substring(0, dosPuntos);
                    puerto = enteroEnRango(valor.substring(dosPuntos + 1), 1, 65535, "puerto");
                } else if (arg.startsWith("--timeout=")) {
                    timeoutMs = enteroEnRango(arg.substring("--timeout=".length()), 100, 600_000, "timeout");
                } else if (arg.startsWith("--reintentos=")) {
                    reintentos = enteroEnRango(arg.substring("--reintentos=".length()), 0, 10, "reintentos");
                } else {
                    throw new IllegalArgumentException("Argumento no reconocido: " + arg);
                }
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Error en los argumentos: " + e.getMessage());
            imprimirAyuda();
            System.exit(2);
            return;
        }

        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));
        try (ConexionGt conexion = new ConexionGt(host, puerto, timeoutMs)) {
            new ClienteMain(conexion, teclado, reintentos).ejecutar();
        }
        System.out.println("Cliente finalizado. Socket y contexto ZeroMQ cerrados.");
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
        System.out.println("Uso: cliente.ClienteMain [--gt=host:puerto] [--timeout=ms] [--reintentos=n]");
        System.out.println("  --gt          dirección del Gestor de Transacciones (defecto localhost:5555)");
        System.out.println("  --timeout     espera máxima por respuesta en ms (defecto 5000)");
        System.out.println("  --reintentos  reintentos automáticos SOLO para CONSULTA (defecto 2)");
    }

    // ------------------------------------------------------------------ menú

    void ejecutar() {
        System.out.println("==========================================");
        System.out.println("[Cliente SRB] Gestor de Transacciones: " + conexion.getEndpoint());
        System.out.println("[Cliente SRB] Tiempo límite por solicitud: " + conexion.getTimeoutMs() + " ms");
        System.out.println("==========================================");
        try {
            boolean continuar = true;
            while (continuar) {
                System.out.println();
                System.out.println("----- MENÚ -----");
                System.out.println("1. Consultar todos los eventos");
                System.out.println("2. Reservar asientos");
                System.out.println("3. Ver disponibilidad de un evento");
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
                    case "0":
                        continuar = false;
                        break;
                    default:
                        System.out.println("Opción no válida. Escriba 1, 2, 3 o 0.");
                }
            }
        } catch (FinDeEntrada e) {
            System.out.println();
            System.out.println("Entrada cerrada.");
        }
    }

    // ------------------------------------------------------------------ opción 1

    private void opcionConsultar() {
        Map<String, EventoDto> eventos = consultarConReintentos();
        if (eventos != null) {
            imprimirTabla(eventos);
        }
    }

    // ------------------------------------------------------------------ opción 3

    private void opcionVerEvento() {
        Map<String, EventoDto> eventos = consultarConReintentos();
        if (eventos == null) {
            return;
        }
        String id = pedirIdEvento(eventos);
        if (id != null) {
            imprimirEvento(eventos.get(id));
        }
    }

    // ------------------------------------------------------------------ opción 2

    private void opcionReservar() {
        // 1) Catálogo fresco: así el usuario elige con datos actuales y validamos contra ellos.
        Map<String, EventoDto> eventos = consultarConReintentos();
        if (eventos == null) {
            System.out.println("No se puede reservar sin consultar primero el catálogo.");
            return;
        }

        // 2) Evento
        String id = pedirIdEvento(eventos);
        if (id == null) {
            return;
        }
        EventoDto evento = eventos.get(id);
        imprimirEvento(evento);

        // 3) Ocurrencia (si solo hay una, se elige automáticamente)
        int ocurrencia;
        if (evento.getOcurrencias() == 1) {
            ocurrencia = 1;
            System.out.println("El evento tiene una sola ocurrencia; se usará la ocurrencia 1.");
        } else {
            Integer elegida = pedirOcurrencia(evento);
            if (elegida == null) {
                return;
            }
            ocurrencia = elegida;
        }

        // 4) Cantidad
        Integer cantidad = pedirCantidad();
        if (cantidad == null) {
            return;
        }
        int disponiblesAntes = evento.getDisponibles(ocurrencia);
        if (cantidad > disponiblesAntes) {
            System.out.println("Aviso: según la última consulta solo hay " + disponiblesAntes
                    + " asientos. El servidor probablemente rechazará la solicitud.");
        }

        // 5) Confirmación
        String mensaje = ProtocoloSrb.mensajeReserva(id, ocurrencia, cantidad);
        if (!confirmar("¿Reservar " + cantidad + " asiento(s) en " + id + ", ocurrencia " + ocurrencia
                + " (" + evento.getFecha(ocurrencia) + ")? (s/n): ")) {
            System.out.println("Reserva cancelada por el usuario; no se envió nada.");
            return;
        }

        // 6) Envío: UN solo intento. Una reserva NO es idempotente: si se pierde la respuesta
        //    y reintentamos, el servidor podría descontar los asientos dos veces.
        System.out.println("Enviando: " + mensaje);
        long inicio = System.nanoTime();
        try {
            String respuesta = conexion.enviar(mensaje);
            double latenciaMs = (System.nanoTime() - inicio) / 1_000_000.0;
            ResultadoReserva resultado = ResultadoReserva.desde(respuesta);

            if (resultado == ResultadoReserva.EXITOSA) {
                System.out.printf("RESERVA EXITOSA (respuesta \"%s\" en %.2f ms)%n", respuesta, latenciaMs);
                mostrarCambio(id, ocurrencia, disponiblesAntes, cantidad);
            } else {
                System.out.printf("RESERVA RECHAZADA por el servidor (respuesta \"%s\" en %.2f ms).%n",
                        respuesta, latenciaMs);
                System.out.println("Motivo más probable: no hay suficientes asientos disponibles en esa ocurrencia.");
            }
        } catch (ErrorComunicacionException e) {
            System.out.println("FALLO DE COMUNICACIÓN [" + e.getTipo() + "]: " + e.getMessage());
            if (e.pudoHaberseProcesado()) {
                System.out.println("La reserva pudo haberse aplicado o no. NO se reintenta automáticamente;");
                System.out.println("consulte la disponibilidad (opción 3) antes de volver a intentarlo.");
            } else {
                System.out.println("La solicitud no salió del cliente, así que no se aplicó ninguna reserva.");
            }
        }
    }

    /** Consulta de nuevo para comprobar la disponibilidad después de una reserva exitosa. */
    private void mostrarCambio(String id, int ocurrencia, int antes, int cantidad) {
        Map<String, EventoDto> eventos = consultarConReintentos();
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

    /**
     * Envía CONSULTA. Como consultar NO modifica datos (es idempotente), sí es seguro
     * reintentar cuando falla la comunicación.
     *
     * @return eventos ordenados, o null si todos los intentos fallaron
     */
    private Map<String, EventoDto> consultarConReintentos() {
        int intentos = 1 + reintentosConsulta;
        for (int i = 1; i <= intentos; i++) {
            long inicio = System.nanoTime();
            try {
                String respuesta = conexion.enviar(ProtocoloSrb.CONSULTA);
                Map<String, EventoDto> eventos = ProtocoloSrb.parsearConsulta(respuesta);
                double latenciaMs = (System.nanoTime() - inicio) / 1_000_000.0;
                System.out.printf("[CONSULTA] %d eventos recibidos en %.2f ms%n", eventos.size(), latenciaMs);
                return eventos;
            } catch (ErrorComunicacionException e) {
                System.out.println("[CONSULTA] Intento " + i + "/" + intentos + " fallido ["
                        + e.getTipo() + "]: " + e.getMessage());
                if (e.getTipo() == ErrorComunicacionException.Tipo.RESPUESTA_INVALIDA) {
                    return null; // el servidor contestó algo inválido; reintentar no lo arregla
                }
            }
        }
        System.out.println("[CONSULTA] El Gestor de Transacciones no está disponible.");
        return null;
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

    /** @return id válido, o null si el usuario deja la línea vacía para cancelar */
    private String pedirIdEvento(Map<String, EventoDto> eventos) {
        while (true) {
            String texto = leer("Identificador del evento (ej. E7, vacío para cancelar): ");
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

    private Integer pedirOcurrencia(EventoDto evento) {
        while (true) {
            String texto = leer("Ocurrencia (1-" + evento.getOcurrencias() + ", vacío para cancelar): ");
            if (texto.isBlank()) {
                return null;
            }
            try {
                return ValidadorEntrada.validarOcurrencia(texto, evento);
            } catch (IllegalArgumentException e) {
                System.out.println("  " + e.getMessage());
            }
        }
    }

    private Integer pedirCantidad() {
        while (true) {
            String texto = leer("Cantidad de asientos (vacío para cancelar): ");
            if (texto.isBlank()) {
                return null;
            }
            try {
                return ValidadorEntrada.validarCantidad(texto);
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
