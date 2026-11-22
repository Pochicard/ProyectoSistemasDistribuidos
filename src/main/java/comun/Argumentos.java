package comun;

import java.util.HashMap;
import java.util.Map;

/**
 * Lectura de argumentos de línea de comandos con la forma {@code --clave=valor}.
 *
 * <p>Existe para que NINGUNA dirección de red quede escrita en el código: cada servicio
 * recibe por argumento la IP y el puerto de la capa siguiente. Así el mismo .jar se
 * ejecuta en una sola máquina (todo en localhost) o en tres máquinas distintas
 * cambiando únicamente los argumentos.</p>
 *
 * <p>Orden de prioridad para cada valor: argumento {@code --clave=valor} &gt; variable de
 * entorno (p. ej. {@code SRB_RMC}) &gt; valor por defecto.</p>
 */
public final class Argumentos {

    private final Map<String, String> valores = new HashMap<>();

    public Argumentos(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Argumento no reconocido: " + arg + " (use --clave=valor)");
            }
            int igual = arg.indexOf('=');
            if (igual < 0) {
                valores.put(arg.substring(2), "true"); // banderas como --reiniciar
            } else {
                valores.put(arg.substring(2, igual), arg.substring(igual + 1).trim());
            }
        }
    }

    public boolean tiene(String clave) {
        return valores.containsKey(clave);
    }

    /** Valor del argumento, si no de la variable de entorno SRB_&lt;CLAVE&gt;, si no el defecto. */
    public String texto(String clave, String defecto) {
        if (valores.containsKey(clave)) {
            return valores.get(clave);
        }
        String env = System.getenv("SRB_" + clave.toUpperCase().replace('-', '_'));
        return (env != null && !env.isBlank()) ? env.trim() : defecto;
    }

    public int entero(String clave, int defecto) {
        String v = texto(clave, String.valueOf(defecto));
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + clave + " debe ser un número entero: " + v);
        }
    }

    /**
     * Lee una dirección {@code host:puerto} y devuelve el endpoint ZeroMQ {@code tcp://host:puerto}.
     */
    public String endpoint(String clave, String defecto) {
        String v = texto(clave, defecto);
        int dosPuntos = v.lastIndexOf(':');
        if (dosPuntos <= 0 || dosPuntos == v.length() - 1) {
            throw new IllegalArgumentException("--" + clave + " debe tener la forma host:puerto, p. ej. 192.168.1.20:5556");
        }
        Integer.parseInt(v.substring(dosPuntos + 1)); // valida que el puerto sea numérico
        return "tcp://" + v;
    }
}
