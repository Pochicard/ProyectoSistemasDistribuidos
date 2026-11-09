package persistencia;


public class Evento {

    public static final int CAPACIDAD_POR_DEFECTO = 100;

    private String id;
    private int ocurrencias;
    private String fecha1;
    private String fecha2;
    private int capacidad1;
    private int capacidad2;
    private int asientosDisponibles1;
    private int asientosDisponibles2;

    public Evento() {
    }

    public Evento(String id, int ocurrencias, String fecha1, String fecha2) {
        this(id, ocurrencias);
        setOcurrencia(1, fecha1, CAPACIDAD_POR_DEFECTO, CAPACIDAD_POR_DEFECTO);
        if (ocurrencias == 2) {
            setOcurrencia(2, fecha2, CAPACIDAD_POR_DEFECTO, CAPACIDAD_POR_DEFECTO);
        }
    }

    public Evento(String id, int ocurrencias) {
        this.id = id;
        this.ocurrencias = ocurrencias;
    }

    public void setOcurrencia(int numero, String fecha, int capacidad, int disponibles) {
        if (numero == 1) {
            fecha1 = fecha;
            capacidad1 = capacidad;
            asientosDisponibles1 = disponibles;
        } else {
            fecha2 = fecha;
            capacidad2 = capacidad;
            asientosDisponibles2 = disponibles;
        }
    }

    public String getId() {
        return id;
    }

    public int getOcurrencias() {
        return ocurrencias;
    }

    public String getFecha(int ocurrencia) {
        return ocurrencia == 1 ? fecha1 : fecha2;
    }


    public int getCapacidad(int ocurrencia) {
        int cap = ocurrencia == 1 ? capacidad1 : capacidad2;
        return cap > 0 ? cap : Math.max(CAPACIDAD_POR_DEFECTO, getDisponibles(ocurrencia));
    }

    public int getDisponibles(int ocurrencia) {
        return ocurrencia == 1 ? asientosDisponibles1 : asientosDisponibles2;
    }

    public int getOcupados(int ocurrencia) {
        return getCapacidad(ocurrencia) - getDisponibles(ocurrencia);
    }
}
