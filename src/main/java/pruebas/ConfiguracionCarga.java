package pruebas;

import cliente.ConexionGt;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Parámetros de una prueba de carga y escenarios A, B y C del enunciado. La lista completa de parámetros
 * se muestra con {@code pruebas.GeneradorCargaMain --ayuda}.
 */
public final class ConfiguracionCarga {

    public static final int RPM_MINIMO = 20;

    String nombre = "A";
    int clientes = 3;
    int soloConsulta = 2;
    int duracionSeg = 150;
    int rpm = 30;
    int pctReserva = 50;
    int pctModificacion = 0;
    int pctCancelacion = 0;
    String protocolo = "v2";
    int asientosMin = 1;
    int asientosMax = 2;
    SelectorOcurrencia.Estrategia seleccion = SelectorOcurrencia.Estrategia.ALEATORIA;
    String eventoFijo = "E1";
    int ocurrenciaFija = 1;
    List<String> gts = List.of("localhost:5555");
    String ip = "127.0.0.1";
    int puertoNotif = 6100;
    int esperaNotificacionesSeg = 5;
    int timeoutMs = 5000;
    Path carpetaSalida = Paths.get("resultados");
    long semilla = 42;
    int descartarSeg = 0;

    /** Configuración predefinida de los escenarios A, B o C. */
    public static ConfiguracionCarga escenario(String letra) {
        ConfiguracionCarga c = new ConfiguracionCarga();
        c.aplicarEscenario(letra);
        return c;
    }

    private void aplicarEscenario(String letra) {
        switch (letra.trim().toUpperCase(Locale.ROOT)) {
            case "A":
                nombre = "A";
                clientes = 3;
                soloConsulta = 2;
                break;
            case "B":
                nombre = "B";
                clientes = 5;
                soloConsulta = 3;
                pctModificacion = 20; // el enunciado pide reserva y modificación en B y C
                break;
            case "C":
                nombre = "C";
                clientes = 8;
                soloConsulta = 4;
                pctModificacion = 20;
                break;
            default:
                throw new IllegalArgumentException("Escenario desconocido: " + letra + " (use A, B o C)");
        }
    }

    /** Construye la configuración a partir de los argumentos de línea de comandos. */
    public static ConfiguracionCarga desdeArgumentos(String[] args) {
        ConfiguracionCarga c = new ConfiguracionCarga();
        boolean personalizado = false;
        for (String arg : args) {
            int igual = arg.indexOf('=');
            if (!arg.startsWith("--") || igual < 0) {
                throw new IllegalArgumentException("Argumento no reconocido: " + arg + " (formato --nombre=valor)");
            }
            String clave = arg.substring(2, igual);
            String valor = arg.substring(igual + 1).trim();
            switch (clave) {
                case "escenario":
                    c.aplicarEscenario(valor);
                    break;
                case "clientes":
                    c.clientes = entero(valor, clave);
                    personalizado = true;
                    break;
                case "solo-consulta":
                    c.soloConsulta = entero(valor, clave);
                    personalizado = true;
                    break;
                case "duracion":
                    c.duracionSeg = entero(valor, clave);
                    break;
                case "rpm":
                    c.rpm = entero(valor, clave);
                    break;
                case "pct-reserva":
                    c.pctReserva = entero(valor, clave);
                    break;
                case "pct-modificacion":
                    c.pctModificacion = entero(valor, clave);
                    break;
                case "pct-cancelacion":
                    c.pctCancelacion = entero(valor, clave);
                    break;
                case "protocolo":
                    c.protocolo = valor.toLowerCase(Locale.ROOT);
                    break;
                case "ip":
                    c.ip = valor;
                    break;
                case "puerto-notif":
                    c.puertoNotif = entero(valor, clave);
                    break;
                case "asientos":
                    int guion = valor.indexOf('-');
                    if (guion > 0) {
                        c.asientosMin = entero(valor.substring(0, guion), clave);
                        c.asientosMax = entero(valor.substring(guion + 1), clave);
                    } else {
                        c.asientosMin = entero(valor, clave);
                        c.asientosMax = c.asientosMin;
                    }
                    break;
                case "seleccion":
                    try {
                        c.seleccion = SelectorOcurrencia.Estrategia.valueOf(valor.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("--seleccion debe ser aleatoria, rotacion o fija");
                    }
                    break;
                case "evento":
                    c.eventoFijo = valor.toUpperCase(Locale.ROOT);
                    break;
                case "ocurrencia":
                    c.ocurrenciaFija = entero(valor, clave);
                    break;
                case "gt":
                    c.gts = ConexionGt.parsearDirecciones(valor);
                    break;
                case "timeout":
                    c.timeoutMs = entero(valor, clave);
                    break;
                case "salida":
                    c.carpetaSalida = Paths.get(valor);
                    break;
                case "semilla":
                    try {
                        c.semilla = Long.parseLong(valor);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--semilla debe ser un número entero");
                    }
                    break;
                case "descartar":
                    c.descartarSeg = entero(valor, clave);
                    break;
                default:
                    throw new IllegalArgumentException("Parámetro desconocido: --" + clave);
            }
        }
        if (personalizado) {
            c.nombre = "personalizado";
        }
        c.validar();
        return c;
    }

    /** Valida la coherencia de los parámetros. */
    public void validar() {
        exigir(clientes >= 1 && clientes <= 100, "--clientes debe estar entre 1 y 100");
        exigir(soloConsulta >= 0 && soloConsulta <= clientes, "--solo-consulta debe estar entre 0 y el número de clientes");
        exigir(duracionSeg >= 5 && duracionSeg <= 3600, "--duracion debe estar entre 5 y 3600 segundos");
        exigir(rpm >= RPM_MINIMO && rpm <= 6000, "--rpm debe estar entre " + RPM_MINIMO + " (mínimo del enunciado) y 6000");
        exigir(pctReserva >= 0 && pctModificacion >= 0 && pctCancelacion >= 0
                && pctReserva + pctModificacion + pctCancelacion <= 100,
                "los porcentajes de reserva, modificación y cancelación deben sumar entre 0 y 100");
        exigir(protocolo.equals("v1") || protocolo.equals("v2"), "--protocolo debe ser v1 o v2");
        exigir(usaIdSol() || (pctModificacion == 0 && pctCancelacion == 0),
                "con --protocolo=v1 no hay modificaciones ni cancelaciones (use --pct-modificacion=0)");
        exigir(!ip.isEmpty() && !ip.contains(":"), "--ip debe ser una dirección IPv4 o un nombre de equipo");
        exigir(puertoNotif >= 1 && puertoNotif <= 65535, "--puerto-notif debe estar entre 1 y 65535");
        exigir(asientosMin >= 1 && asientosMax >= asientosMin, "--asientos debe ser positivo (ej. 2 o 1-2)");
        exigir(timeoutMs >= 100 && timeoutMs <= 600_000, "--timeout debe estar entre 100 y 600000 ms");
        exigir(descartarSeg >= 0 && descartarSeg < duracionSeg, "--descartar debe ser menor que la duración");
        exigir(ocurrenciaFija >= 1, "--ocurrencia debe ser positiva");
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) {
            throw new IllegalArgumentException(mensaje);
        }
    }

    private static int entero(String texto, String clave) {
        try {
            return Integer.parseInt(texto.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + clave + " debe ser un número entero: " + texto);
        }
    }

    /** v2 envía idSol en cada mensaje y recibe el número de reserva. */
    public boolean usaIdSol() {
        return protocolo.equals("v2");
    }

    public int clientesMixtos() {
        return clientes - soloConsulta;
    }

    /** Los clientes 1..soloConsulta solo consultan; los demás son mixtos. */
    public PerfilCliente perfilDe(int numeroCliente) {
        return numeroCliente <= soloConsulta ? PerfilCliente.SOLO_CONSULTA : PerfilCliente.MIXTO;
    }

    /** Prefijo de los archivos de resultados, por ejemplo "escenarioA-3clientes". */
    public String prefijoArchivo() {
        return (nombre.length() == 1 ? "escenario" + nombre : nombre) + "-" + clientes + "clientes";
    }

    public String descripcion() {
        return String.format(Locale.ROOT,
                "Escenario %s | %d clientes (%d solo consulta, %d mixto(s)) | %d s | %d solicitudes/min por cliente%n"
                        + "Clientes mixtos: %d%% reservas, %d%% modificaciones, %d%% cancelaciones, %d%% consultas;"
                        + " %s asiento(s), selección %s%s%n"
                        + "GT: %s | protocolo %s | tiempo límite %d ms | semilla %d | descartar primeros %d s",
                nombre, clientes, soloConsulta, clientesMixtos(), duracionSeg, rpm,
                pctReserva, pctModificacion, pctCancelacion, 100 - pctReserva - pctModificacion - pctCancelacion,
                asientosMin == asientosMax ? Integer.toString(asientosMin) : asientosMin + "-" + asientosMax,
                seleccion.name().toLowerCase(Locale.ROOT),
                seleccion == SelectorOcurrencia.Estrategia.FIJA ? " (" + eventoFijo + ":" + ocurrenciaFija + ")" : "",
                String.join(",", gts), protocolo, timeoutMs, semilla, descartarSeg);
    }

    public String getNombre() { return nombre; }
    public int getClientes() { return clientes; }
    public int getDuracionSeg() { return duracionSeg; }
    public int getRpm() { return rpm; }
    public int getDescartarSeg() { return descartarSeg; }
    public Path getCarpetaSalida() { return carpetaSalida; }
}
