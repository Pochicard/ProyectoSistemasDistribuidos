package persistencia;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class BaseDatos implements AutoCloseable {

    public static final int TOTAL_EVENTOS = 50;
    public static final int EVENTOS_CON_DOS_OCURRENCIAS = 20;

    private static final Type TIPO_MAPA = new TypeToken<LinkedHashMap<String, Evento>>() { }.getType();
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Gson gson = new Gson();
    private final Path archivoBd;
    private final Connection con;

    public BaseDatos(Path directorio, boolean reiniciar, Path cargaInicial) {
        try {
            Files.createDirectories(directorio);
            this.archivoBd = directorio.resolve("srb.db");
            this.con = DriverManager.getConnection("jdbc:sqlite:" + archivoBd.toAbsolutePath());
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");   // lecturas no bloquean escrituras
                st.execute("PRAGMA synchronous = FULL");   // COMMIT = dato en disco
            }
            crearEsquema();

            if (cargaInicial != null) {
                cargar(leerJson(cargaInicial));
                registrar("INICIO", "carga inicial desde " + cargaInicial.toAbsolutePath(), "OK");
            } else if (reiniciar || contarEventos() == 0) {
                cargar(generarCargaInicial());
                registrar("INICIO", "carga inicial por defecto (" + TOTAL_EVENTOS + " eventos)", "OK");
            } else {
                registrar("INICIO", "estado recuperado de " + archivoBd.toAbsolutePath(), "OK");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio de datos " + directorio, e);
        } catch (SQLException e) {
            throw new IllegalStateException("Error abriendo la base de datos SQLite: " + e.getMessage(), e);
        }
    }

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
            st.execute("CREATE TABLE IF NOT EXISTS transaccion ("
                    + " id         INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " fecha_hora TEXT NOT NULL,"
                    + " operacion  TEXT NOT NULL,"
                    + " detalle    TEXT,"
                    + " resultado  TEXT NOT NULL)");
        }
    }

    private int contarEventos() throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM evento")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void cargar(Map<String, Evento> eventos) throws SQLException {
        con.setAutoCommit(false);
        try (Statement st = con.createStatement();
             PreparedStatement insEv = con.prepareStatement("INSERT INTO evento(id, ocurrencias) VALUES (?, ?)");
             PreparedStatement insOc = con.prepareStatement(
                     "INSERT INTO ocurrencia(evento_id, numero, fecha, capacidad, disponibles) VALUES (?, ?, ?, ?, ?)")) {
            st.execute("DELETE FROM ocurrencia");
            st.execute("DELETE FROM evento");
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
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(true);
        }
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
            throw new IllegalStateException("El archivo " + archivo + " no es un JSON de eventos válido", e);
        }
    }

    private static Map<String, Evento> generarCargaInicial() {
        Map<String, Evento> mapa = new LinkedHashMap<>();
        for (int i = 1; i <= TOTAL_EVENTOS; i++) {
            String id = "E" + i;
            int dia1 = 1 + (i - 1) % 14;                                   // días 1..14
            String fecha1 = String.format("2026-11-%02d", dia1);
            if (i <= EVENTOS_CON_DOS_OCURRENCIAS) {
                mapa.put(id, new Evento(id, 2, fecha1, String.format("2026-11-%02d", dia1 + 14)));
            } else {
                mapa.put(id, new Evento(id, 1, fecha1, null));
            }
        }
        return mapa;
    }


    public synchronized String consultarJson() {
        try {
            Map<String, Evento> eventos = leerEventos(null);
            registrar("CONSULTA", eventos.size() + " eventos", "OK");
            return gson.toJson(eventos);
        } catch (SQLException e) {
            throw new IllegalStateException("Error en CONSULTA: " + e.getMessage(), e);
        }
    }
    public synchronized boolean hacerReserva(String idEvento, int ocurrencia, int cantidad) {
        String detalle = idEvento + ":" + ocurrencia + ":" + cantidad;
        if (cantidad <= 0) {
            registrar("RESERVA", detalle, "RECHAZADO");
            return false;
        }
        try {
            con.setAutoCommit(false);
            int filas;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE ocurrencia SET disponibles = disponibles - ?"
                            + " WHERE evento_id = ? AND numero = ? AND disponibles >= ?")) {
                ps.setInt(1, cantidad);
                ps.setString(2, idEvento);
                ps.setInt(3, ocurrencia);
                ps.setInt(4, cantidad);
                filas = ps.executeUpdate();
            }
            boolean exito = filas == 1;
            insertarTransaccion("RESERVA", detalle, exito ? "OK" : "RECHAZADO");
            con.commit();
            return exito;
        } catch (SQLException e) {
            try {
                con.rollback();
            } catch (SQLException ignorada) {
            }
            throw new IllegalStateException("Error en RESERVA: " + e.getMessage(), e);
        } finally {
            try {
                con.setAutoCommit(true);
            } catch (SQLException ignorada) {
            }
        }
    }

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
                    Evento ev = mapa.computeIfAbsent(id, k -> {
                        try {
                            return new Evento(k, rs.getInt(2));
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    ev.setOcurrencia(rs.getInt(3), rs.getString(4), rs.getInt(5), rs.getInt(6));
                }
            }
        }
        return mapa;
    }


    private void insertarTransaccion(String operacion, String detalle, String resultado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO transaccion(fecha_hora, operacion, detalle, resultado) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, LocalDateTime.now().format(HORA));
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
            System.err.println("[Persistencia] Aviso: no se pudo registrar la transacción: " + e.getMessage());
        }
    }


    public synchronized String tablaEstado() {
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

    public synchronized String resumen() {
        String sql = "SELECT (SELECT COUNT(*) FROM evento), SUM(disponibles), SUM(capacidad - disponibles) FROM ocurrencia";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return "BD: " + rs.getInt(1) + " eventos | asientos libres=" + rs.getInt(2) + " | ocupados=" + rs.getInt(3);
        } catch (SQLException e) {
            return "BD: error leyendo totales (" + e.getMessage() + ")";
        }
    }

    public synchronized String lineaEvento(String idEvento) {
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
    public synchronized void close() {
        try {
            con.close();
        } catch (SQLException ignorada) {
        }
    }
}
