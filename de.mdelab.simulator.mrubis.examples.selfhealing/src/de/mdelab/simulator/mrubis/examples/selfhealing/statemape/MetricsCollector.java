package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Collector encargado de almacenar las métricas del experimento.
 *
 * Registra:
 * - estrategia de planificación
 * - nivel de falla
 * - repetición experimental
 * - T_ajuste
 * - T_estab
 * - utilidad acumulada
 *
 * Exporta los resultados en formato CSV.
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class MetricsCollector {


    private final FileWriter writer;


    /**
     * Inicializa el archivo CSV.
     *
     * @param path ruta del archivo de salida
     */
    public MetricsCollector(String path) throws IOException {

        writer = new FileWriter(path);

        writer.write(
            "estrategia,cf,repeticion,"
            + "t_ajuste_ms,t_estab_ms,utilidad\n"
        );
    }


    /**
     * Registra una ejecución experimental.
     */
    public void record(
            String estrategia,
            String cf,
            int repeticion,
            long tAjuste,
            long tEstab,
            double utilidad
    ) throws IOException {


        writer.write(
            estrategia + "," +
            cf + "," +
            repeticion + "," +
            tAjuste + "," +
            tEstab + "," +
            String.format("%.6f", utilidad)
            + "\n"
        );

        writer.flush();
    }


    /**
     * Cierra el archivo CSV.
     */
    public void close() throws IOException {

        writer.close();
    }
}