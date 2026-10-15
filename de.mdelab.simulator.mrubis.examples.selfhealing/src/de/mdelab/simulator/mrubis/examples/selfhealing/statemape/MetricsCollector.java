package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Exporta a CSV las métricas de cada repetición del diseño factorial
 * (versión 3: escenarios de ráfaga con presupuesto de reparación).
 *
 * Formato: separador ";", punto decimal (Locale.ROOT). Una fila por
 * repetición, escrita y vaciada (flush) de inmediato. Si el archivo ya
 * existe, el constructor lanza una excepción en lugar de sobrescribirlo.
 *
 * Columnas:
 *   estrategia, escenario (CF1|CF2|CF3|CF5|MIXTO), tamano (fallas de la ráfaga),
 *   capacidad (presupuesto de reparación por ronda), repeticion, semilla
 *   fallas_planificadas       fallas que el escenario logró elegir
 *   inyecciones_exitosas      inyecciones que tuvieron efecto
 *   valida                    1 si la ráfaga produjo pérdida de utilidad, 0 si no
 *   utilidad_sana             utilidad antes de la ráfaga
 *   utilidad_post_falla_r1    utilidad justo después de inyectar la ráfaga
 *   perdida_inicial           utilidad_sana - utilidad_post_falla_r1
 *   t_ajuste_ms, t_estab_ms   tiempos desde el registro de la falla (-1 si no se alcanzó)
 *   rondas_ajuste, rondas_estab   ronda en que se alcanzó cada umbral (-1 si no)
 *   rondas_total              rondas ejecutadas
 *   utilidad_final            utilidad de cierre de la última ronda
 *   utilidad_acum             suma de la utilidad de cierre de cada ronda
 *   perdida_acum              suma de (utilidad_sana - utilidad de cierre) por ronda
 *   problemas_final           problemas de validación al cierre de la última ronda
 *   problemas_acum            suma de los problemas de validación por ronda
 *   reparaciones_ejecutadas   estrategias ejecutadas en total
 *   reparaciones_diferidas    issues detectados que no se repararon en su ronda
 *   t_plan_ms, n_planificaciones   tiempo acumulado y número de ejecuciones de Plan
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class MetricsCollector {

    /** Valores de una repetición. */
    public static class Fila {
        public String estrategia;
        public String escenario;
        public int tamano;
        public double capacidad;
        public int repeticion;
        public long semilla;
        public int fallasPlanificadas;
        public int inyeccionesExitosas;
        public int valida;
        public double utilidadSana;
        public double utilidadPostFallaR1;
        public double perdidaInicial;
        public long tAjusteMs;
        public long tEstabMs;
        public int rondasAjuste;
        public int rondasEstab;
        public int rondasTotal;
        public double utilidadFinal;
        public double utilidadAcum;
        public double perdidaAcum;
        public int problemasFinal;
        public int problemasAcum;
        public int reparacionesEjecutadas;
        public int reparacionesDiferidas;
        public double tPlanMs;
        public int nPlanificaciones;
    }

    private static final String ENCABEZADO =
            "estrategia;escenario;tamano;capacidad;repeticion;semilla;"
            + "fallas_planificadas;inyecciones_exitosas;valida;"
            + "utilidad_sana;utilidad_post_falla_r1;perdida_inicial;"
            + "t_ajuste_ms;t_estab_ms;rondas_ajuste;rondas_estab;rondas_total;"
            + "utilidad_final;utilidad_acum;perdida_acum;"
            + "problemas_final;problemas_acum;"
            + "reparaciones_ejecutadas;reparaciones_diferidas;"
            + "t_plan_ms;n_planificaciones";

    private final BufferedWriter writer;

    /**
     * @param csvPath ruta del CSV de salida; no debe existir previamente.
     * @throws IOException si el archivo ya existe o no puede crearse.
     */
    public MetricsCollector(String csvPath) throws IOException {

        File archivo = new File(csvPath).getAbsoluteFile();

        File carpeta = archivo.getParentFile();

        if (carpeta != null && !carpeta.exists()) {
            carpeta.mkdirs();
        }

        this.writer = Files.newBufferedWriter(
                archivo.toPath(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);

        this.writer.write(ENCABEZADO);
        this.writer.newLine();
        this.writer.flush();
    }

    /**
     * Registra una repetición del experimento.
     */
    public void record(Fila f) {

        String fila = String.format(
                Locale.ROOT,
                "%s;%s;%d;%.1f;%d;%d;%d;%d;%d;%.6f;%.6f;%.6f;%d;%d;%d;%d;%d;%.6f;%.6f;%.6f;%d;%d;%d;%d;%.3f;%d",
                f.estrategia,
                f.escenario,
                f.tamano,
                f.capacidad,
                f.repeticion,
                f.semilla,
                f.fallasPlanificadas,
                f.inyeccionesExitosas,
                f.valida,
                f.utilidadSana,
                f.utilidadPostFallaR1,
                f.perdidaInicial,
                f.tAjusteMs,
                f.tEstabMs,
                f.rondasAjuste,
                f.rondasEstab,
                f.rondasTotal,
                f.utilidadFinal,
                f.utilidadAcum,
                f.perdidaAcum,
                f.problemasFinal,
                f.problemasAcum,
                f.reparacionesEjecutadas,
                f.reparacionesDiferidas,
                f.tPlanMs,
                f.nPlanificaciones);

        try {
            this.writer.write(fila);
            this.writer.newLine();
            this.writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Cierra el archivo.
     */
    public void close() throws IOException {
        this.writer.close();
    }
}