package persistencia;

public class Evento {
    private String id;
    private int ocurrencias;
    private String fecha1;
    private String fecha2;
    private int asientosDisponibles1;
    private int asientosDisponibles2;

    public Evento(String id, int ocurrencias, String fecha1, String fecha2) {
        this.id = id;
        this.ocurrencias = ocurrencias;
        this.fecha1 = fecha1;
        this.fecha2 = fecha2;
        this.asientosDisponibles1 = 100; // 100 asientos iniciales por defecto[cite: 5]
        this.asientosDisponibles2 = (ocurrencias == 2) ? 100 : 0;
    }

    public String getId() {
        return id;
    }

    public int getOcurrencias() {
        return ocurrencias;
    }

    public String getFecha1() {
        return fecha1;
    }

    public String getFecha2() {
        return fecha2;
    }

    public int getAsientosDisponibles1() {
        return asientosDisponibles1;
    }

    public int getAsientosDisponibles2() {
        return asientosDisponibles2;
    }

    // Método sincronizado para evitar problemas de concurrencia al reservar
    public synchronized boolean reservar(int ocurrencia, int cantidad) {
        if (ocurrencia == 1 && asientosDisponibles1 >= cantidad) {
            asientosDisponibles1 -= cantidad;
            return true;
        } else if (ocurrencia == 2 && asientosDisponibles2 >= cantidad) {
            asientosDisponibles2 -= cantidad;
            return true;
        }
        return false;
    }
}
