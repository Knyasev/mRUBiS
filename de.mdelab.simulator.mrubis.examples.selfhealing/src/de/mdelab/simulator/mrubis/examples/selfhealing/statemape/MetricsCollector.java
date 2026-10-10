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
 * Exporta a CSV las métricas de cada repetición del diseño factorial.
 *
 * Formato: separador ";" y punto decimal (Locale.ROOT). Una fila por
 * repetición. Cada fila se escribe y se vacía (flush) de inmediato, de modo
 * que una interrupción de la corrida no pierde lo ya registrado.
 *
 * Si el archivo ya existe, el constructor lanza una excepción en lugar de
 * sobrescribirlo (protege los datasets de corridas anteriores).
 *
 * Columnas:
 *   estrategia, cf, repeticion
 *   t_ajuste_ms, t_estab_ms            tiempos desde el registro de la falla
 *                                      (-1 si no se alcanzó el umbral)
 *   rondas_ajuste, rondas_estab        ronda en que se alcanzó cada umbral
 *                                      (-1 si no se alcanzó)
 *   rondas_total                       rondas ejecutadas en la repetición
 *   utilidad_sana                      computeUtility() antes de la falla
 *   utilidad_final                     computeUtility() al final de la última ronda
 *   utilidad_acum                      suma de computeUtility() al final de cada ronda
 *   utilidad_acum_postfalla            suma de computeUtility() justo después de
 *                                      inyectar la falla, antes de actuar
 *   t_plan_ms                          tiempo acumulado en la fase Plan
 *   n_planificaciones                  rondas en las que se ejecutó la fase Plan
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class MetricsCollector {

    private static final String ENCABEZADO =
            "estrategia;cf;repeticion;t_ajuste_ms;t_estab_ms;"
            + "rondas_ajuste;rondas_estab;rondas_total;"
            + "utilidad_sana;utilidad_final;utilidad_acum;"
            + "utilidad_acum_postfalla;t_plan_ms;n_planificaciones";

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
     */
    public void record(
            String estrategia,
            String cf,
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
            int nPlanificaciones) {

        String fila = String.format(
                Locale.ROOT,
                "%s;%s;%d;%d;%d;%d;%d;%d;%.6f;%.6f;%.6f;%.6f;%.3f;%d",
                estrategia,
                cf,
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
                nPlanificaciones);

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