package pruebas;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Escritura concurrente de los CSV mediante el patrón productor-consumidor.
 *
 * <p>Los clientes simulados solo agregan registros a una cola concurrente; un único hilo escritor los
 * vuelca a disco. De esta forma las filas no se mezclan y la escritura en disco no afecta la latencia
 * medida por los clientes.</p>
 *
 * <p>Al cerrar se encola el marcador {@link #FIN}; el hilo escritor procesa los registros pendientes
 * antes de terminar, por lo que no se pierde ninguno.</p>
 */
public final class EscritorCsv {

    /** Marcador de fin de escritura. Se compara por referencia y nunca se escribe. */
    private static final RegistroTransaccion FIN = new RegistroTransaccion(
            -1, Instant.EPOCH, "", PerfilCliente.SOLO_CONSULTA, TipoOperacion.CONSULTA,
            0, EstadoTransaccion.OK, null, null, null);

    private final BlockingQueue<RegistroTransaccion> cola = new LinkedBlockingQueue<>();
    private final BufferedWriter csv;
    private final BufferedWriter csvExcel;
    private final Thread hiloEscritor;
    private final AtomicLong escritos = new AtomicLong();
    private volatile IOException error;
    private volatile boolean cerrado;

    /**
     * @param rutaCsv      CSV estándar (separador ',')
     * @param rutaCsvExcel CSV para Excel en español (separador ';', coma decimal); puede ser null
     */
    public EscritorCsv(Path rutaCsv, Path rutaCsvExcel) throws IOException {
        this.csv = Files.newBufferedWriter(rutaCsv, StandardCharsets.UTF_8);
        this.csv.write(RegistroTransaccion.ENCABEZADO);
        this.csv.newLine();
        if (rutaCsvExcel != null) {
            this.csvExcel = Files.newBufferedWriter(rutaCsvExcel, StandardCharsets.UTF_8);
            this.csvExcel.write('﻿'); // BOM para que Excel detecte UTF-8
            this.csvExcel.write(RegistroTransaccion.ENCABEZADO_EXCEL);
            this.csvExcel.newLine();
        } else {
            this.csvExcel = null;
        }
        this.hiloEscritor = new Thread(this::bucleEscritura, "escritor-csv");
        this.hiloEscritor.start();
    }

    /** Agrega un registro a la cola. Puede invocarse desde cualquier hilo y no realiza E/S. */
    public void registrar(RegistroTransaccion registro) {
        if (cerrado) {
            throw new IllegalStateException("El escritor CSV ya fue cerrado");
        }
        cola.add(registro);
    }

    private void bucleEscritura() {
        try {
            while (true) {
                RegistroTransaccion r = cola.poll();
                if (r == null) {
                    // Con la cola vacía se vuelcan los buffers antes de esperar el siguiente registro.
                    vaciarBuffers();
                    r = cola.take();
                }
                if (r == FIN) {
                    break;
                }
                csv.write(r.aCsv());
                csv.newLine();
                if (csvExcel != null) {
                    csvExcel.write(r.aCsvExcel());
                    csvExcel.newLine();
                }
                escritos.incrementAndGet();
            }
        } catch (IOException e) {
            error = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cerrarArchivos();
        }
    }

    private void vaciarBuffers() throws IOException {
        csv.flush();
        if (csvExcel != null) {
            csvExcel.flush();
        }
    }

    private void cerrarArchivos() {
        try {
            csv.close();
        } catch (IOException e) {
            if (error == null) {
                error = e;
            }
        }
        if (csvExcel != null) {
            try {
                csvExcel.close();
            } catch (IOException e) {
                if (error == null) {
                    error = e;
                }
            }
        }
    }

    /** Número de registros escritos. */
    public long getEscritos() {
        return escritos.get();
    }

    /** Error de escritura ocurrido, o null si no hubo ninguno. */
    public IOException getError() {
        return error;
    }

    public boolean hiloTerminado() {
        return !hiloEscritor.isAlive();
    }

    /** Encola el marcador de fin y espera a que el hilo escritor cierre los archivos. */
    public void close() throws InterruptedException {
        if (cerrado) {
            return;
        }
        cerrado = true;
        cola.add(FIN);
        hiloEscritor.join();
    }
}
