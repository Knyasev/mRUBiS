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
 * Exporta a CSV la TRAZA POR RONDA de cada repetición (versión 3): una fila
 * por ronda. Sirve para graficar la evolución de la utilidad y para
 * recalcular métricas con otros umbrales sin repetir el experimento.
 *
 * Formato: separador ";", punto decimal (Locale.ROOT). Si el archivo ya
 * existe, el constructor lanza una excepción en lugar de sobrescribirlo.
 *
 * Columnas:
 *   estrategia, escenario, tamano, capacidad, repeticion, ronda
 *   utilidad_post_falla      utilidad al inicio del ciclo MAPE-K de la ronda
 *   utilidad_cierre          utilidad tras validateModel() (cierre de ronda)
 *   problemas_validacion     valor que devuelve validateModel()
 *   issues_detectados        Issues que crea Analyze en la ronda
 *   reparaciones_ejecutadas  estrategias que ejecuta Execute en la ronda
 *   t_plan_ms                tiempo de la fase Plan de la ronda
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class TrazaRondas {

    private static final String ENCABEZADO =
            "estrategia;escenario;tamano;capacidad;repeticion;ronda;"
            + "utilidad_post_falla;utilidad_cierre;problemas_validacion;"
            + "issues_detectados;reparaciones_ejecutadas;t_plan_ms";

    private final BufferedWriter writer;

    public TrazaRondas(String csvPath) throws IOException {

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
     * Registra una ronda de una repetición.
     */
    public void record(
            String estrategia,
            String escenario,
            int tamano,
            double capacidad,
            int repeticion,
            StateBasedMapeFeedbackLoop.RondaTraza r) {

        String fila = String.format(
                Locale.ROOT,
                "%s;%s;%d;%.1f;%d;%d;%.6f;%.6f;%d;%d;%d;%.3f",
                estrategia,
                escenario,
                tamano,
                capacidad,
                repeticion,
                r.ronda,
                r.utilidadPostFalla,
                r.utilidadCierre,
                r.problemasValidacion,
                r.issuesDetectados,
                r.reparacionesEjecutadas,
                r.tPlanMs);

        try {
            this.writer.write(fila);
            this.writer.newLine();
            this.writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void close() throws IOException {
        this.writer.close();
    }
}
