package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Collector encargado de almacenar las métricas del experimento.
 *
 * Registra:
 * - Estrategia de planificación
 * - Nivel de falla (CF)
 * - Repetición experimental
 * - Tiempo de ajuste (T_ajuste)
 * - Tiempo de estabilización (T_estab)
 * - Utilidad acumulada
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

        // Separador ';' para evitar conflictos con Excel
        writer.write(
                "estrategia;cf;repeticion;"
              + "t_ajuste_ms;t_estab_ms;utilidad\n");
    }

    /**
     * Registra una ejecución experimental.
     *
     * @param estrategia Estrategia utilizada
     * @param cf Nivel de falla crítica
     * @param repeticion Número de repetición
     * @param tAjuste Tiempo de ajuste (ms)
     * @param tEstab Tiempo de estabilización (ms)
     * @param utilidad Utilidad acumulada
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
                estrategia + ";" +
                cf + ";" +
                repeticion + ";" +
                tAjuste + ";" +
                tEstab + ";" +
                String.format(Locale.US, "%.6f", utilidad) +
                "\n"
        );

        // Guarda inmediatamente cada registro
        writer.flush();
    }

    /**
     * Cierra el archivo CSV.
     */
    public void close() throws IOException {
        writer.close();
    }
}