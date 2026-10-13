package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogManager;

import de.mdelab.comparch.Architecture;
import de.mdelab.comparch.Component;
import de.mdelab.comparch.ProvidedInterface;
import de.mdelab.comparch.Tenant;
import de.mdelab.simulator.Scenario;
import de.mdelab.simulator.Simulator;
import de.mdelab.simulator.mrubis.examples.selfhealing.SelfHealingConfig;
import de.mdelab.simulator.mrubis.examples.selfhealing.MultiFaultSelfHealingScenario;
import de.mdelab.simulator.mrubis.examples.selfhealing.MultiFaultSelfHealingScenario.CFLevel;
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;

/**
 * DIAGNÓSTICO TEMPORAL. No forma parte del experimento y no modifica
 * StateBasedMapeFeedbackLoop. Se puede borrar al terminar.
 *
 * Ejecuta UNA repetición por combinación (estrategia, nivel CF, carga)
 * y, en cada ronda, mide excepciones y utilidad en cuatro puntos:
 *
 *   Pre   justo después de injectIssues()         (falla recién inyectada)
 *   Post  después de Monitor-Analyze-Plan-Execute (fin del ciclo MAPE-K)
 *   Val   después de simulator.validateModel()    (tras los validadores)
 *   Ini   (solo para excIny) excepciones al cierre de la ronda anterior
 *
 * Columnas de salida:
 *   r          número de ronda
 *   fallas     fallas simultáneas configuradas por ronda
 *   issues     Issues que crea Analyze en esa ronda
 *   excIny     excepciones que aparecen entre el cierre de la ronda anterior
 *              y el punto Pre (inyección; negativo = algo las borró)
 *   excPre/Post/Val       total de excepciones en el modelo
 *   sobrePre/Post/Val     componentes con alguna interfaz sobre el umbral
 *   uPre/uPost/uVal       utilidad del sistema
 *   valIssues  valor que devuelve validateModel()
 *
 * El resultado se imprime en consola y se guarda en
 * ./results/diagnostico_cf2_rafagas.txt (se sobrescribe en cada ejecución).
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class DiagnosticoCF2 {

    private static final String MODEL_URI = "./model/mRUBiS.comparch";

    private static final int EXCEPTION_THRESHOLD = 5;

    private static final int MIN_PIPE_SIZE = 4;

    private static final int ROUNDS = 20;

    // Solo para el diagnóstico (debe ser mayor que 0).
    private static final long INJECTION_DELAY_MS = 10;

    // === Cambiar para diagnosticar otras combinaciones ===

    private static final StateBasedMapeFeedbackLoop.PlanningStrategy[] ESTRATEGIAS = {
            StateBasedMapeFeedbackLoop.PlanningStrategy.STATIC
            //, StateBasedMapeFeedbackLoop.PlanningStrategy.UTILITY_DRIVEN
            //, StateBasedMapeFeedbackLoop.PlanningStrategy.SOLVER
    };

    private static final CFLevel[] NIVELES = {
            CFLevel.CF2
    };

    /**
     * Cargas a diagnosticar (fallas simultáneas por ronda).
     * 1  = línea base (comportamiento del escenario original)
     * 10 = ráfaga media
     * 50 = ráfaga grande
     */
    private static final int[] CARGAS = {
            1
            //, 10
            //, 50
    };


    public static void main(String[] args) throws IOException {

        StringBuilder salida = new StringBuilder();

        for (StateBasedMapeFeedbackLoop.PlanningStrategy estrategia : ESTRATEGIAS) {
            for (CFLevel nivel : NIVELES) {
                for (int carga : CARGAS) {
                    ejecutar(estrategia, nivel, carga, salida);
                }
            }
        }

        File archivo = new File("./results/diagnostico_cf2_rafagas.txt");
        File carpeta = archivo.getAbsoluteFile().getParentFile();
        if (carpeta != null && !carpeta.exists()) {
            carpeta.mkdirs();
        }
        try (PrintWriter pw = new PrintWriter(archivo, "UTF-8")) {
            pw.print(salida.toString());
        }

        System.out.println();
        System.out.println(salida.toString());
        System.out.println("Diagnóstico guardado en: " + archivo.getAbsolutePath());
    }


    private static void ejecutar(
            StateBasedMapeFeedbackLoop.PlanningStrategy estrategia,
            CFLevel nivel,
            int carga,
            StringBuilder salida) {

        Architecture architecture = SelfHealingConfig.loadModel(MODEL_URI);

        MRubisSelfHealingUtilityFunction utilityFunction =
                new MRubisSelfHealingUtilityFunction(EXCEPTION_THRESHOLD);

        // === NUEVO: escenario con ráfagas ===
        Scenario scenario = new MultiFaultSelfHealingScenario(
                architecture, EXCEPTION_THRESHOLD, nivel, carga);

        Simulator simulator = Simulator.FACTORY.createSimulator(
                architecture,
                utilityFunction,
                scenario,
                ROUNDS,
                INJECTION_DELAY_MS,
                false,
                Level.WARNING,
                false);

        SelfHealingConfig.addValidators(simulator, EXCEPTION_THRESHOLD, MIN_PIPE_SIZE);

        double utilidadSana = utilityFunction.computeUtility(architecture);

        salida.append(String.format(Locale.ROOT,
                "%n===== [DIAG] estrategia=%s  cf=%s  carga=%d  utilidadSana=%.6f  umbral0.90=%.4f =====%n",
                estrategia.name(), nivel.name(), carga, utilidadSana, 0.90 * utilidadSana));
        salida.append("[DIAG] r fallas issues excIny excPre excPost excVal "
                + "sobrePre sobrePost sobreVal uPre uPost uVal valIssues\n");

        int excCierreAnterior = contarExcepciones(architecture);

        int rondasSinEfecto = 0;
        int rondasConInyeccion = 0;
        int rondasConBorradoEnInyeccion = 0;
        int rondasConBorradoEnExecute = 0;
        int rondasConBorradoEnValidacion = 0;

        int ronda = 0;

        do {

            simulator.injectIssues();
            ronda++;

            // --- punto Pre: falla recién inyectada ---
            double uPre = utilityFunction.computeUtility(architecture);
            int excPre = contarExcepciones(architecture);
            int sobrePre = componentesSobreUmbral(architecture);

            // --- ciclo MAPE-K ---
            new Monitor().monitor(architecture);
            new Analyze().analyze(architecture, EXCEPTION_THRESHOLD);
            int nIssues = architecture.getAnnotations().getIssues().size();
            planificar(estrategia, architecture);
            new Execute().execute(architecture);

            // --- punto Post: fin del ciclo MAPE-K ---
            double uPost = utilityFunction.computeUtility(architecture);
            int excPost = contarExcepciones(architecture);
            int sobrePost = componentesSobreUmbral(architecture);

            // --- punto Val: tras los validadores ---
            int valIssues = simulator.validateModel();
            double uVal = utilityFunction.computeUtility(architecture);
            int excVal = contarExcepciones(architecture);
            int sobreVal = componentesSobreUmbral(architecture);

            int excIny = excPre - excCierreAnterior;

            if (excIny > 0) {
                rondasConInyeccion++;
            } else if (excIny == 0) {
                rondasSinEfecto++;
            } else {
                rondasConBorradoEnInyeccion++;
            }
            if (excPost < excPre) {
                rondasConBorradoEnExecute++;
            }
            if (excVal < excPost) {
                rondasConBorradoEnValidacion++;
            }

            salida.append(String.format(Locale.ROOT,
                    "[DIAG] %d %d %d %d %d %d %d %d %d %d %.4f %.4f %.4f %d%n",
                    ronda, carga, nIssues, excIny, excPre, excPost, excVal,
                    sobrePre, sobrePost, sobreVal, uPre, uPost, uVal, valIssues));

            excCierreAnterior = excVal;

        } while (!simulator.isSimulationCompleted());

        salida.append(String.format(Locale.ROOT,
                "[DIAG] RESUMEN carga=%d: rondas con excepciones inyectadas=%d | sin efecto=%d | "
                + "con borrado entre rondas=%d | borrado en Execute=%d | borrado en validateModel=%d%n",
                carga, rondasConInyeccion, rondasSinEfecto, rondasConBorradoEnInyeccion,
                rondasConBorradoEnExecute, rondasConBorradoEnValidacion));

        // Libera el FileHandler de Simulator.log.
        LogManager.getLogManager().reset();
    }


    private static void planificar(
            StateBasedMapeFeedbackLoop.PlanningStrategy estrategia,
            Architecture architecture) {

        switch (estrategia) {

        case STATIC:
            new Plan().plan(architecture);
            break;

        case UTILITY_DRIVEN:
            new UtilityDrivenPlan(EXCEPTION_THRESHOLD).plan(architecture);
            break;

        case SOLVER:
            new SolverPlan().plan(architecture);
            break;
        }
    }


    /** Total de excepciones en todas las interfaces provistas del modelo. */
    private static int contarExcepciones(Architecture architecture) {
        int total = 0;
        for (Tenant tenant : architecture.getTenants()) {
            for (Component component : tenant.getComponents()) {
                for (ProvidedInterface pi : component.getProvidedInterfaces()) {
                    total += pi.getExceptions().size();
                }
            }
        }
        return total;
    }


    /** Componentes con al menos una interfaz sobre el umbral de excepciones. */
    private static int componentesSobreUmbral(Architecture architecture) {
        int total = 0;
        for (Tenant tenant : architecture.getTenants()) {
            for (Component component : tenant.getComponents()) {
                for (ProvidedInterface pi : component.getProvidedInterfaces()) {
                    if (pi.getExceptions().size() > EXCEPTION_THRESHOLD) {
                        total++;
                        break;
                    }
                }
            }
        }
        return total;
    }

}