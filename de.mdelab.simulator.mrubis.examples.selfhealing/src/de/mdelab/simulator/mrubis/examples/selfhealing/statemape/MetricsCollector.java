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
 * Exporta a CSV las metricas de cada repeticion del diseno factorial.
 *
 * v3: se anade la columna problemas_pendientes, que registra lo que
 * devuelve simulator.validateModel() en cada repeticion (cantidad de
 * problemas detectados tras la validacion).
 *
 * Formato: separador ";" y punto decimal (Locale.ROOT). Una fila por
 * repeticion. Cada fila se escribe y se vacia (flush) de inmediato.
 *
 * Si el archivo ya existe, el constructor lanza una excepcion en lugar
 * de sobrescribirlo.
 *
 * Columnas:
 *   estrategia, cf, fallas_por_ronda, repeticion
 *   t_ajuste_ms, t_estab_ms
 *   rondas_ajuste, rondas_estab, rondas_total
 *   utilidad_sana, utilidad_final, utilidad_acum
 *   utilidad_acum_postfalla
 *   t_plan_ms, n_planificaciones
 *   problemas_pendientes                      (v3)
 *
 * Contribucion tecnica original 
 * Wilson Israel Gonzalez Armijos
 */
public class MetricsCollector {

    private static final String ENCABEZADO =
            "estrategia;cf;fallas_por_ronda;repeticion;"
            + "t_ajuste_ms;t_estab_ms;"
            + "rondas_ajuste;rondas_estab;rondas_total;"
            + "utilidad_sana;utilidad_final;utilidad_acum;"
            + "utilidad_acum_postfalla;t_plan_ms;n_planificaciones;"
            + "problemas_pendientes";

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

        // CREATE_NEW falla (FileAlreadyExistsException) si el archivo existe.
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
     *
     * v3: nuevo parámetro problemasPendientes.
     */
    public void record(
            String estrategia,
            String cf,
            int fallasPorRonda,
            int repeticion,
            long tAjusteMs,
            long tEstabMs,
            int rondasAjuste,
            int rondasEstab,
            int rondasTotal,
            double utilidadSana,
            double utilidadFinal,
            double utilidadAcum,
            double utilidadAcumPostFalla,
            double tPlanMs,
            int nPlanificaciones,
            int problemasPendientes) {

        String fila = String.format(
                Locale.ROOT,
                "%s;%s;%d;%d;%d;%d;%d;%d;%d;%.6f;%.6f;%.6f;%.6f;%.3f;%d;%d",
                estrategia,
                cf,
                fallasPorRonda,
                repeticion,
                tAjusteMs,
                tEstabMs,
                rondasAjuste,
                rondasEstab,
                rondasTotal,
                utilidadSana,
                utilidadFinal,
                utilidadAcum,
                utilidadAcumPostFalla,
                tPlanMs,
                nPlanificaciones,
                problemasPendientes);

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