package persistencia;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import comun.Protocolo;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Acceso a la base de datos SQLite del SRB ({@code datos/srb.db}).
 *
 * <h2>Esquema</h2>
 * <pre>
 * evento      (id PK, ocurrencias)
 * ocurrencia  (evento_id FK, numero, fecha, capacidad, disponibles)  PK(evento_id, numero)
 *             CHECK (disponibles BETWEEN 0 AND capacidad)
 * reserva     (id autoincremental, evento_id, numero, cantidad, estado ACTIVA|CANCELADA, creada, actualizada)
 * solicitud   (id = idSol del cliente, operacion, respuesta, fecha)   ← idempotencia
 * transaccion (id, fecha_hora, operacion, detalle, resultado)        ← bitacora
 * </pre>
 *
 * <h2>Concurrencia</h2>
 * El Servicio de Persistencia atiende con varios hilos; cada hilo tiene su PROPIA instancia de esta
 * clase (una conexion JDBC no se comparte entre hilos). SQLite en modo WAL permite que las consultas
 * corran en paralelo, y las escrituras se ordenan solas porque cada transaccion empieza con
 * {@code BEGIN IMMEDIATE} (toma el permiso de escritura al inicio y espera hasta 10 s si otro hilo
 * lo tiene). Asi nunca se sobrevende ni se pierde una actualizacion.
 *
 * <h2>Idempotencia</h2>
 * Cada operacion con {@code idSol} se registra en la tabla {@code solicitud} en la MISMA transaccion
 * que el cambio. Si llega otra vez el mismo {@code idSol} (un reintento del cliente o del GT tras una
 * falla), se devuelve la respuesta guardada sin volver a aplicar la operacion.
 */
public class BaseDatos implements AutoCloseable {

    public static final int TOTAL_EVENTOS = 50;
    public static final int EVENTOS_CON_DOS_OCURRENCIAS = 20;

    private static final Type TIPO_MAPA = new TypeToken<LinkedHashMap<String, Evento>>() { }.getType();
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Resultado de una operacion de escritura: la respuesta para el cliente y datos para la consola. */
    public record Resultado(String respuesta, String evento, boolean duplicada) {
    }

    private final Gson gson = new Gson();
    private final Path archivoBd;
    private final Connection con;

    /** Abre una conexion a {@code <directorio>/srb.db} y crea las tablas que falten. */
    public BaseDatos(Path directorio) {
        try {
            Files.createDirectories(directorio);
            this.archivoBd = directorio.resolve("srb.db");
            SQLiteConfig cfg = new SQLiteConfig();
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);          // lecturas en paralelo
            cfg.setSynchronous(SQLiteConfig.SynchronousMode.FULL);     // COMMIT = dato en disco
            cfg.enforceForeignKeys(true);
            cfg.setBusyTimeout(10_000);                                // espera si otro hilo escribe
            cfg.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            this.con = cfg.createConnection("jdbc:sqlite:" + archivoBd.toAbsolutePath());
            crearEsquema();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio de datos " + directorio, e);
        } catch (SQLException e) {
            throw new IllegalStateException("Error abriendo la base de datos SQLite: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ esquema y carga inicial

    private void crearEsquema() throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS evento ("
                    + " id          TEXT PRIMARY KEY,"
                    + " ocurrencias INTEGER NOT NULL CHECK (ocurrencias IN (1, 2)))");
            st.execute("CREATE TABLE IF NOT EXISTS ocurrencia ("
                    + " evento_id   TEXT    NOT NULL REFERENCES evento(id) ON DELETE CASCADE,"
                    + " numero      INTEGER NOT NULL CHECK (numero IN (1, 2)),"
                    + " fecha       TEXT    NOT NULL,"
                    + " capacidad   INTEGER NOT NULL CHECK (capacidad >= 0),"
                    + " disponibles INTEGER NOT NULL,"
                    + " PRIMARY KEY (evento_id, numero),"
                    + " CHECK (disponibles BETWEEN 0 AND capacidad))");
            st.execute("CREATE TABLE IF NOT EXISTS reserva ("
                    + " id          INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " evento_id   TEXT    NOT NULL,"
                    + " numero      INTEGER NOT NULL,"
                    + " cantidad    INTEGER NOT NULL CHECK (cantidad > 0),"
                    + " estado      TEXT    NOT NULL CHECK (estado IN ('ACTIVA', 'CANCELADA')),"
                    + " creada      TEXT    NOT NULL,"
                    + " actualizada TEXT    NOT NULL,"
                    + " FOREIGN KEY (evento_id, numero) REFERENCES ocurrencia(evento_id, numero))");
            st.execute("CREATE TABLE IF NOT EXISTS solicitud ("
                    + " id        TEXT PRIMARY KEY,"
                    + " operacion TEXT NOT NULL,"
                    + " respuesta TEXT NOT NULL,"
                    + " fecha     TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS transaccion ("
                    + " id         INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " fecha_hora TEXT NOT NULL,"
                    + " operacion  TEXT NOT NULL,"
                    + " detalle    TEXT,"
                    + " resultado  TEXT NOT NULL)");
        }
    }

    /**
     * Deja la BD lista para atender. Se llama UNA vez, antes de arrancar los hilos.
     *
     * @param reiniciar    true para borrar todo y volver a la carga inicial
     * @param cargaInicial JSON opcional con otra carga inicial (mismo formato que CONSULTA); null = 50 eventos
     */
    public void prepararDatos(boolean reiniciar, Path cargaInicial) {
        try {
            if (cargaInicial != null) {
                cargar(leerJson(cargaInicial));
                registrar("INICIO", "carga inicial desde " + cargaInicial.toAbsolutePath(), "OK");
            } else if (reiniciar || contarEventos() == 0) {
                cargar(generarCargaInicial());
                registrar("INICIO", "carga inicial por defecto (" + TOTAL_EVENTOS + " eventos)", "OK");
            } else {
                registrar("INICIO", "estado recuperado de " + archivoBd.toAbsolutePath(), "OK");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error preparando los datos: " + e.getMessage(), e);
        }
    }

    private int contarEventos() throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM evento")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Borra reservas, solicitudes, bitacora y eventos, y carga los indicados (una transaccion). */
    private void cargar(Map<String, Evento> eventos) throws SQLException {
        enTransaccion(() -> {
            try (Statement st = con.createStatement();
                 PreparedStatement insEv = con.prepareStatement("INSERT INTO evento(id, ocurrencias) VALUES (?, ?)");
                 PreparedStatement insOc = con.prepareStatement(
                         "INSERT INTO ocurrencia(evento_id, numero, fecha, capacidad, disponibles) VALUES (?, ?, ?, ?, ?)")) {
                st.execute("DELETE FROM reserva");
                st.execute("DELETE FROM solicitud");
                st.execute("DELETE FROM transaccion");
                st.execute("DELETE FROM ocurrencia");
                st.execute("DELETE FROM evento");
                st.execute("DELETE FROM sqlite_sequence WHERE name IN ('reserva', 'transaccion')");
                for (Evento e : eventos.values()) {
                    insEv.setString(1, e.getId());
                    insEv.setInt(2, e.getOcurrencias());
                    insEv.executeUpdate();
                    for (int o = 1; o <= e.getOcurrencias(); o++) {
                        insOc.setString(1, e.getId());
                        insOc.setInt(2, o);
                        insOc.setString(3, e.getFecha(o));
                        insOc.setInt(4, e.getCapacidad(o));
                        insOc.setInt(5, e.getDisponibles(o));
                        insOc.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    private Map<String, Evento> leerJson(Path archivo) {
        try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            Map<String, Evento> leidos = gson.fromJson(r, TIPO_MAPA);
            if (leidos == null || leidos.isEmpty()) {
                throw new IllegalStateException("El archivo " + archivo + " no contiene eventos");
            }
            return leidos;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + archivo, e);
        } catch (JsonParseException e) {
            throw new IllegalStateException("El archivo " + archivo + " no es un JSON de eventos valido", e);
        }
    }

    /** E1..E20: dos ocurrencias en el mes. E21..E50: una. 100 asientos por ocurrencia. */
    private static Map<String, Evento> generarCargaInicial() {
        Map<String, Evento> mapa = new LinkedHashMap<>();
        for (int i = 1; i <= TOTAL_EVENTOS; i++) {
            String id = "E" + i;
            int dia1 = 1 + (i - 1) % 14;
            String fecha1 = String.format("2026-11-%02d", dia1);
            if (i <= EVENTOS_CON_DOS_OCURRENCIAS) {
                mapa.put(id, new Evento(id, 2, fecha1, String.format("2026-11-%02d", dia1 + 14)));
            } else {
                mapa.put(id, new Evento(id, 1, fecha1, null));
            }
        }
        return mapa;
    }

    // ------------------------------------------------------------------ operaciones

    /** Todos los eventos como JSON (respuesta de CONSULTA), ordenados E1, E2, ..., E50. */
    public String consultarJson() {
        try {
            return gson.toJson(leerEventos(null));
        } catch (SQLException e) {
            throw new IllegalStateException("Error en CONSULTA: " + e.getMessage(), e);
        }
    }

    /**
     * Reserva asientos. El descuento es UNA sentencia condicional ({@code ... AND disponibles >= ?}),
     * asi que nunca se sobrevende aunque varios hilos reserven a la vez.
     *
     * @param idSol null para el formato de la primera entrega (responde "OK" sin idReserva)
     */
    public Resultado reservar(String idSol, String evento, int ocurrencia, int cantidad) {
        String detalle = evento + ":" + ocurrencia + ":" + cantidad;
        return operacionIdempotente(idSol, Protocolo.RESERVA, detalle, evento, () -> {
            if (cantidad <= 0) {
                return Protocolo.RECHAZADO;
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE ocurrencia SET disponibles = disponibles - ?"
                            + " WHERE evento_id = ? AND numero = ? AND disponibles >= ?")) {
                ps.setInt(1, cantidad);
                ps.setString(2, evento);
                ps.setInt(3, ocurrencia);
                ps.setInt(4, cantidad);
                if (ps.executeUpdate() != 1) {
                    return Protocolo.RECHAZADO;
                }
            }
            long idReserva;
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reserva(evento_id, numero, cantidad, estado, creada, actualizada)"
                            + " VALUES (?, ?, ?, 'ACTIVA', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                String ahora = ahora();
                ps.setString(1, evento);
                ps.setInt(2, ocurrencia);
                ps.setInt(3, cantidad);
                ps.setString(4, ahora);
                ps.setString(5, ahora);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    idReserva = k.getLong(1);
                }
            }
            return Protocolo.okReserva(idReserva, idSol != null);
        });
    }

    /**
     * Modifica una reserva activa: cambia la cantidad de asientos y/o la ocurrencia (fecha) del mismo
     * evento. Devuelve los asientos viejos y toma los nuevos en una sola transaccion; si no hay cupo
     * para los nuevos, no cambia nada.
     */
    public Resultado modificar(String idSol, long idReserva, int ocurrenciaNueva, int cantidadNueva) {
        String detalle = "reserva " + idReserva + " -> ocur " + ocurrenciaNueva + ", " + cantidadNueva + " asientos";
        String evento = eventoDeReserva(idReserva);
        return operacionIdempotente(idSol, Protocolo.MODIFICAR, detalle, evento, () -> {
            Reserva r = leerReservaActiva(idReserva);
            if (r == null || cantidadNueva <= 0) {
                return Protocolo.RECHAZADO;
            }
            Savepoint antes = con.setSavepoint();
            ajustarDisponibles(r.evento, r.ocurrencia, +r.cantidad);                 // devuelve los viejos
            if (!descontar(r.evento, ocurrenciaNueva, cantidadNueva)) {               // toma los nuevos
                con.rollback(antes);
                return Protocolo.RECHAZADO;
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE reserva SET numero = ?, cantidad = ?, actualizada = ? WHERE id = ?")) {
                ps.setInt(1, ocurrenciaNueva);
                ps.setInt(2, cantidadNueva);
                ps.setString(3, ahora());
                ps.setLong(4, idReserva);
                ps.executeUpdate();
            }
            return Protocolo.OK;
        });
    }

    /** Cancela una reserva activa y devuelve sus asientos (la usa el Servicio de Cancelaciones). */
    public Resultado liberar(String idSol, long idReserva) {
        String evento = eventoDeReserva(idReserva);
        return operacionIdempotente(idSol, Protocolo.LIBERAR, "reserva " + idReserva, evento, () -> {
            Reserva r = leerReservaActiva(idReserva);
            if (r == null) {
                return Protocolo.RECHAZADO;
            }
            ajustarDisponibles(r.evento, r.ocurrencia, +r.cantidad);
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE reserva SET estado = 'CANCELADA', actualizada = ? WHERE id = ?")) {
                ps.setString(1, ahora());
                ps.setLong(2, idReserva);
                ps.executeUpdate();
            }
            return Protocolo.OK;
        });
    }

    // ------------------------------------------------------------------ apoyo a las operaciones

    private record Reserva(String evento, int ocurrencia, int cantidad) {
    }

    private Reserva leerReservaActiva(long idReserva) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT evento_id, numero, cantidad FROM reserva WHERE id = ? AND estado = 'ACTIVA'")) {
            ps.setLong(1, idReserva);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Reserva(rs.getString(1), rs.getInt(2), rs.getInt(3)) : null;
            }
        }
    }

    private String eventoDeReserva(long idReserva) {
        try (PreparedStatement ps = con.prepareStatement("SELECT evento_id FROM reserva WHERE id = ?")) {
            ps.setLong(1, idReserva);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private boolean descontar(String evento, int ocurrencia, int cantidad) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ocurrencia SET disponibles = disponibles - ?"
                        + " WHERE evento_id = ? AND numero = ? AND disponibles >= ?")) {
            ps.setInt(1, cantidad);
            ps.setString(2, evento);
            ps.setInt(3, ocurrencia);
            ps.setInt(4, cantidad);
            return ps.executeUpdate() == 1;
        }
    }

    private void ajustarDisponibles(String evento, int ocurrencia, int delta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE ocurrencia SET disponibles = disponibles + ? WHERE evento_id = ? AND numero = ?")) {
            ps.setInt(1, delta);
            ps.setString(2, evento);
            ps.setInt(3, ocurrencia);
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface Trabajo<T> {
        T ejecutar() throws SQLException;
    }

    /** Ejecuta en una transaccion (BEGIN IMMEDIATE): o se aplica todo o nada. */
    private <T> T enTransaccion(Trabajo<T> trabajo) throws SQLException {
        con.setAutoCommit(false);
        try {
            T r = trabajo.ejecutar();
            con.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(true);
        }
    }

    /**
     * Ejecuta una escritura una sola vez por {@code idSol}. Si el idSol ya existe, devuelve la respuesta
     * guardada. La consulta del idSol, el cambio y el registro van en la misma transaccion.
     */
    private Resultado operacionIdempotente(String idSol, String operacion, String detalle, String evento,
                                           Trabajo<String> cambio) {
        try {
            return enTransaccion(() -> {
                if (idSol != null) {
                    String previa = respuestaGuardada(idSol);
                    if (previa != null) {
                        insertarTransaccion(operacion, detalle + " (idSol repetido)", "DUPLICADA -> " + previa);
                        return new Resultado(previa, evento, true);
                    }
                }
                String respuesta = cambio.ejecutar();
                if (idSol != null) {
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO solicitud(id, operacion, respuesta, fecha) VALUES (?, ?, ?, ?)")) {
                        ps.setString(1, idSol);
                        ps.setString(2, operacion);
                        ps.setString(3, respuesta);
                        ps.setString(4, ahora());
                        ps.executeUpdate();
                    }
                }
                insertarTransaccion(operacion, detalle, respuesta);
                return new Resultado(respuesta, evento, false);
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Error en " + operacion + ": " + e.getMessage(), e);
        }
    }

    private String respuestaGuardada(String idSol) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT respuesta FROM solicitud WHERE id = ?")) {
            ps.setString(1, idSol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Lee eventos con sus ocurrencias; idEvento null = todos. */
    private Map<String, Evento> leerEventos(String idEvento) throws SQLException {
        String sql = "SELECT e.id, e.ocurrencias, o.numero, o.fecha, o.capacidad, o.disponibles"
                + " FROM evento e JOIN ocurrencia o ON o.evento_id = e.id"
                + (idEvento != null ? " WHERE e.id = ?" : "")
                + " ORDER BY CAST(SUBSTR(e.id, 2) AS INTEGER), e.id, o.numero";
        Map<String, Evento> mapa = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            if (idEvento != null) {
                ps.setString(1, idEvento);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    Evento ev = mapa.get(id);
                    if (ev == null) {
                        ev = new Evento(id, rs.getInt(2));
                        mapa.put(id, ev);
                    }
                    ev.setOcurrencia(rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getInt(6));
                }
            }
        }
        return mapa;
    }

    // ------------------------------------------------------------------ bitacora

    private void insertarTransaccion(String operacion, String detalle, String resultado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO transaccion(fecha_hora, operacion, detalle, resultado) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, ahora());
            ps.setString(2, operacion);
            ps.setString(3, detalle);
            ps.setString(4, resultado);
            ps.executeUpdate();
        }
    }

    private void registrar(String operacion, String detalle, String resultado) {
        try {
            insertarTransaccion(operacion, detalle, resultado);
        } catch (SQLException e) {
            System.err.println("[Persistencia] Aviso: no se pudo registrar la transaccion: " + e.getMessage());
        }
    }

    private static String ahora() {
        return LocalDateTime.now().format(HORA);
    }

    // ------------------------------------------------------------------ presentacion

    /** Tabla con el estado de la BD, para mostrar en la sustentacion. */
    public String tablaEstado() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-6s %-4s %-11s %7s %7s %7s%n", "Evento", "Ocur", "Fecha", "Cap.", "Libres", "Ocup."));
        try {
            for (Evento e : leerEventos(null).values()) {
                for (int o = 1; o <= e.getOcurrencias(); o++) {
                    sb.append(String.format("%-6s %-4d %-11s %7d %7d %7d%n", e.getId(), o, e.getFecha(o),
                            e.getCapacidad(o), e.getDisponibles(o), e.getOcupados(o)));
                }
            }
        } catch (SQLException ex) {
            sb.append("Error leyendo la BD: ").append(ex.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /** Una linea con totales, para imprimir despues de cada escritura. */
    public String resumen() {
        String sql = "SELECT (SELECT COUNT(*) FROM evento), SUM(disponibles), SUM(capacidad - disponibles),"
                + " (SELECT COUNT(*) FROM reserva WHERE estado = 'ACTIVA') FROM ocurrencia";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return "BD: " + rs.getInt(1) + " eventos | libres=" + rs.getInt(2) + " | ocupados=" + rs.getInt(3)
                    + " | reservas activas=" + rs.getInt(4);
        } catch (SQLException e) {
            return "BD: error leyendo totales (" + e.getMessage() + ")";
        }
    }

    /** Estado de un evento en una linea. */
    public String lineaEvento(String idEvento) {
        if (idEvento == null) {
            return "";
        }
        try {
            Evento e = leerEventos(idEvento).get(idEvento);
            if (e == null) {
                return idEvento + ": no existe";
            }
            StringBuilder sb = new StringBuilder(idEvento + ":");
            for (int o = 1; o <= e.getOcurrencias(); o++) {
                sb.append(" ocur").append(o).append(" (").append(e.getFecha(o)).append(") libres=")
                        .append(e.getDisponibles(o)).append(" ocupados=").append(e.getOcupados(o)).append(';');
            }
            return sb.toString();
        } catch (SQLException ex) {
            return idEvento + ": error leyendo la BD (" + ex.getMessage() + ")";
        }
    }

    public Path getArchivoBd() {
        return archivoBd;
    }

    @Override
    public void close() {
        try {
            con.close();
        } catch (SQLException ignorada) {
            // al cerrar no hay nada mas que hacer
        }
    }
}
