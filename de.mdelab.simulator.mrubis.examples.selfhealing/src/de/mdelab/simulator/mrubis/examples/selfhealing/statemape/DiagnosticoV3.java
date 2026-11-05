package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogManager;

import de.mdelab.comparch.AdaptationStrategy;
import de.mdelab.comparch.Architecture;
import de.mdelab.comparch.Component;
import de.mdelab.comparch.ComponentState;
import de.mdelab.comparch.ProvidedInterface;
import de.mdelab.comparch.Tenant;
import de.mdelab.simulator.Simulator;
import de.mdelab.simulator.mrubis.MRubisNames;
import de.mdelab.simulator.mrubis.examples.selfhealing.BurstScenario;
import de.mdelab.simulator.mrubis.examples.selfhealing.BurstScenario.Perfil;
import de.mdelab.simulator.mrubis.examples.selfhealing.SelfHealingConfig;
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;

/**
 * DIAGNÓSTICO TEMPORAL de la versión 3 (v3).
 *
 * Propósito:
 * 1. Imprime el tamaño del modelo (tenants, componentes y componentes
 *    elegibles como objetivo de una falla).
 * 2. Reproduce UNA ráfaga concreta (misma semilla que main() para la
 *    repetición indicada) con cada estrategia, mostrando por ronda qué
 *    estrategias de adaptación se ejecutan y la utilidad, y deja que los
 *    validadores impriman por consola sus problemas (nivel SEVERE).
 *
 * Salida: consola y ./results/diagnostico_v3.txt (el archivo solo contiene el
 * resumen por ronda; los mensajes de los validadores salen solo por consola).
 */
public class DiagnosticoV3 {

    private static final String MODEL_URI = "./model/mRUBiS.comparch";

    private static final int EXCEPTION_THRESHOLD = 5;

    private static final int MIN_PIPE_SIZE = 4;

    // Con 4 rondas basta para ver la recuperación y los problemas residuales.
    private static final int ROUNDS = 4;

    private static final long INJECTION_DELAY_MS = 10;

    // Configuración a reproducir (misma fórmula de semilla que main()).
    private static final Perfil ESCENARIO = Perfil.MIXTO;

    private static final int TAMANO = 6;

    private static final int REPETICION = 1;

    private static final double CAPACIDAD = 40.0;

    // Umbrales de la versión 3 (v3) definidos sobre la pérdida inicial (L0).
    private static final double FRACCION_RECUPERACION_AJUSTE = 0.50;
    private static final double FRACCION_RECUPERACION_ESTAB = 0.95;

    private static final StateBasedMapeFeedbackLoop.PlanningStrategy[] ESTRATEGIAS = {
            StateBasedMapeFeedbackLoop.PlanningStrategy.STATIC,
            StateBasedMapeFeedbackLoop.PlanningStrategy.UTILITY_DRIVEN,
            StateBasedMapeFeedbackLoop.PlanningStrategy.SOLVER
    };


    public static void main(String[] args) throws IOException {

        StringBuilder salida = new StringBuilder();

        imprimirModelo(salida);

        long semilla =
                1_000_003L * REPETICION
                + 10_007L * (ESCENARIO.ordinal() + 1)
                + TAMANO;

        for (StateBasedMapeFeedbackLoop.PlanningStrategy estrategia : ESTRATEGIAS) {
            ejecutar(estrategia, semilla, salida);
        }

        File archivo = new File("./results/diagnostico_v3.txt");
        File carpeta = archivo.getAbsoluteFile().getParentFile();
        if (carpeta != null && !carpeta.exists()) {
            carpeta.mkdirs();
        }
        try (PrintWriter pw = new PrintWriter(archivo, "UTF-8")) {
            pw.print(salida.toString());
        }

        System.out.println();
        System.out.println(salida.toString());
        System.out.println("Resumen guardado en: " + archivo.getAbsolutePath());
    }


    private static void imprimirModelo(StringBuilder salida) {

        Architecture architecture = SelfHealingConfig.loadModel(MODEL_URI);

        int total = 0;
        int elegibles = 0;
        salida.append(String.format(Locale.ROOT, "%n===== [DIAG] MODELO =====%n"));
        salida.append("[DIAG] tenants=" + architecture.getTenants().size() + "\n");

        int i = 0;
        for (Tenant tenant : architecture.getTenants()) {
            int deTenant = tenant.getComponents().size();
            int elegiblesTenant = 0;
            for (Component c : tenant.getComponents()) {
                if (c.getState() == ComponentState.STARTED && !esFiltro(c)) {
                    elegiblesTenant++;
                }
            }
            total += deTenant;
            elegibles += elegiblesTenant;
            if (i < 5) {
                salida.append("[DIAG] tenant " + i + ": componentes=" + deTenant
                        + " elegibles(STARTED, no filtro)=" + elegiblesTenant + "\n");
            }
            i++;
        }
        salida.append("[DIAG] componentes totales=" + total
                + " | elegibles como objetivo=" + elegibles + "\n");
        
        salida.append(String.format(Locale.ROOT,
                "[DIAG] umbrales v3: ajuste=%.2f estab=%.2f (sobre pérdida inicial)%n",
                FRACCION_RECUPERACION_AJUSTE, FRACCION_RECUPERACION_ESTAB));
    }


    private static void ejecutar(
            StateBasedMapeFeedbackLoop.PlanningStrategy estrategia,
            long semilla,
            StringBuilder salida) {

        Architecture architecture = SelfHealingConfig.loadModel(MODEL_URI);

        MRubisSelfHealingUtilityFunction utilityFunction =
                new MRubisSelfHealingUtilityFunction(EXCEPTION_THRESHOLD);

        BurstScenario scenario = new BurstScenario(
                architecture, EXCEPTION_THRESHOLD, ESCENARIO, TAMANO, semilla);

        // logToConsole = true y nivel SEVERE: los validadores imprimen sus
        // problemas ([ISSUE]) por consola.
        Simulator simulator = Simulator.FACTORY.createSimulator(
                architecture,
                utilityFunction,
                scenario,
                ROUNDS,
                INJECTION_DELAY_MS,
                false,
                Level.SEVERE,
                true);

        SelfHealingConfig.addValidators(simulator, EXCEPTION_THRESHOLD, MIN_PIPE_SIZE);

        double utilidadSana = utilityFunction.computeUtility(architecture);

        salida.append(String.format(Locale.ROOT,
                "%n===== [DIAG] estrategia=%s escenario=%s k=%d capacidad=%.0f semilla=%d utilidadSana=%.4f =====%n",
                estrategia.name(), ESCENARIO.name(), TAMANO, CAPACIDAD, semilla, utilidadSana));

        int ronda = 0;

        do {

            simulator.injectIssues();
            ronda++;

            double uPre = utilityFunction.computeUtility(architecture);

            new Monitor().monitor(architecture);
            new Analyze().analyze(architecture, EXCEPTION_THRESHOLD);
            int detectadas = architecture.getAnnotations().getIssues().size();

            planificar(estrategia, architecture);
            PresupuestoReparacion.aplicar(architecture, CAPACIDAD);

            StringBuilder ejecutadas = new StringBuilder();
            for (AdaptationStrategy s : architecture.getAnnotations().getAdaptationStrategies()) {
                if (ejecutadas.length() > 0) {
                    ejecutadas.append(", ");
                }
                ejecutadas.append(s.getType());
            }

            new Execute().execute(architecture);

            int problemas = simulator.validateModel();
            double uCierre = utilityFunction.computeUtility(architecture);

            salida.append(String.format(Locale.ROOT,
                    "[DIAG] ronda=%d uPre=%.4f uCierre=%.4f problemas=%d issuesDetectados=%d ejecuta=[%s]%n",
                    ronda, uPre, uCierre, problemas, detectadas, ejecutadas.toString()));

        } while (!simulator.isSimulationCompleted());

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
            new SolverPlan(CAPACIDAD).plan(architecture);
            break;
        }
    }


    private static boolean esFiltro(Component component) {
        for (ProvidedInterface pi : component.getProvidedInterfaces()) {
            if (pi.getType().getFqName().equals(MRubisNames.itemFilterService_InterfaceType_FqName)) {
                return true;
            }
        }
        return false;
    }

}