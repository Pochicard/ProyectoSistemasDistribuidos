package pruebas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Punto de entrada del generador de carga. Sin argumentos muestra un menú de escenarios; con
 * {@code --ayuda} lista los parámetros. Ctrl+C detiene la prueba de forma ordenada y guarda los resultados.
 */
public class GeneradorCargaMain {

    public static void main(String[] args) {
        ConfiguracionCarga cfg;
        try {
            if (args.length == 0) {
                cfg = elegirDesdeMenu();
                if (cfg == null) {
                    return;
                }
            } else if (args.length == 1 && (args[0].equals("--ayuda") || args[0].equals("-h"))) {
                imprimirAyuda();
                return;
            } else {
                cfg = ConfiguracionCarga.desdeArgumentos(args);
            }
        } catch (IllegalArgumentException e) {
            System.out.println("Error en los parámetros: " + e.getMessage());
            imprimirAyuda();
            System.exit(2);
            return;
        }

        AtomicBoolean detener = new AtomicBoolean(false);
        CountDownLatch terminado = new CountDownLatch(1);
        Thread ganchoCierre = new Thread(() -> {
            if (terminado.getCount() > 0) {
                System.out.println("\n[Generador] Ctrl+C recibido: deteniendo clientes y guardando resultados...");
                detener.set(true);
                try {
                    terminado.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "cierre-ordenado");
        Runtime.getRuntime().addShutdownHook(ganchoCierre);

        int codigo = 0;
        try {
            GeneradorCarga.ResultadoEjecucion r = new GeneradorCarga(System.out).ejecutar(cfg, detener);
            System.out.println("Resumen guardado en: " + r.resumen().toAbsolutePath());
            System.out.println("Acumulado de todas las pruebas: "
                    + cfg.getCarpetaSalida().resolve(GeneradorCarga.ACUMULADO).toAbsolutePath());
            System.out.println("Antes de la siguiente prueba independiente, arranque Persistencia con --reiniciar.");
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.out.println("[Generador] No se pudo ejecutar la prueba: " + e.getMessage());
            codigo = 1;
        } catch (IOException e) {
            System.out.println("[Generador] Error de archivos: " + e.getMessage());
            codigo = 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            codigo = 1;
        } finally {
            terminado.countDown();
        }
        if (codigo != 0) {
            System.exit(codigo);
        }
    }

    /** Menú de selección de escenario cuando no se reciben argumentos. */
    private static ConfiguracionCarga elegirDesdeMenu() {
        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("=== Generador de carga SRB ===");
        System.out.println("1. Escenario A: 3 clientes (2 solo consulta, 1 mixto)");
        System.out.println("2. Escenario B: 5 clientes (3 solo consulta, 2 mixtos)");
        System.out.println("3. Escenario C: 8 clientes (4 solo consulta, 4 mixtos)");
        System.out.println("4. Prueba corta: escenario A durante 15 s");
        System.out.println("0. Salir");
        try {
            String opcion = leer(teclado, "Opción: ");
            ConfiguracionCarga cfg;
            switch (opcion) {
                case "1": cfg = ConfiguracionCarga.escenario("A"); break;
                case "2": cfg = ConfiguracionCarga.escenario("B"); break;
                case "3": cfg = ConfiguracionCarga.escenario("C"); break;
                case "4":
                    cfg = ConfiguracionCarga.escenario("A");
                    cfg.nombre = "prueba"; // evita mezclar sus archivos con los del escenario A
                    cfg.duracionSeg = 15;
                    return cfg;
                default:
                    return null;
            }
            String duracion = leer(teclado, "Duración en segundos [150]: ");
            if (!duracion.isEmpty()) {
                cfg.duracionSeg = Integer.parseInt(duracion);
            }
            cfg.validar();
            return cfg;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("La duración debe ser un número entero");
        }
    }

    private static String leer(BufferedReader teclado, String mensaje) {
        System.out.print(mensaje);
        System.out.flush();
        try {
            String linea = teclado.readLine();
            return linea == null ? "" : linea.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void imprimirAyuda() {
        System.out.println("Uso: pruebas.GeneradorCargaMain [--escenario=A|B|C] [parámetros]");
        System.out.println("  --escenario=A|B|C       A=3 clientes (2 consulta+1 mixto), B=5 (3+2), C=8 (4+4)");
        System.out.println("  --clientes=N --solo-consulta=K   escenario personalizado");
        System.out.println("  --duracion=150          segundos");
        System.out.println("  --rpm=30                solicitudes por minuto por cliente (mínimo 20)");
        System.out.println("  --pct-reserva=50        % de reservas de los clientes mixtos");
        System.out.println("  --pct-modificacion=20   % de modificaciones de los mixtos (B y C: 20; A: 0)");
        System.out.println("  --pct-cancelacion=0     % de cancelaciones de los mixtos (el resto son consultas)");
        System.out.println("  --protocolo=v2          v2 con idSol; v1 = formato de la primera entrega");
        System.out.println("  --asientos=1-2          asientos por reserva (fijo o rango)");
        System.out.println("  --seleccion=aleatoria   aleatoria | rotacion | fija (con --evento=E1 --ocurrencia=1)");
        System.out.println("  --gt=localhost:5555     GT o lista primario,respaldo (ej. 192.168.56.101:5555,192.168.56.101:5565)");
        System.out.println("  --ip=127.0.0.1          IP de este equipo para las notificaciones de cancelación");
        System.out.println("  --puerto-notif=6100     puerto local de las notificaciones");
        System.out.println("  --timeout=5000          espera máxima por respuesta (ms)");
        System.out.println("  --salida=resultados     carpeta de resultados");
        System.out.println("  --semilla=42            semilla de aleatoriedad");
        System.out.println("  --descartar=0           segundos iniciales excluidos de las métricas");
    }
}
