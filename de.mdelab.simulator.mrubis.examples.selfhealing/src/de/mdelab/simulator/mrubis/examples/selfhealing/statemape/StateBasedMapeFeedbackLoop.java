package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogManager;

import de.mdelab.comparch.Architecture;
import de.mdelab.simulator.Scenario;
import de.mdelab.simulator.Simulator;
import de.mdelab.simulator.UtilityFunction;

import de.mdelab.simulator.mrubis.examples.selfhealing.SelfHealingConfig;
import de.mdelab.simulator.mrubis.examples.selfhealing.MultiFaultSelfHealingScenario;
import de.mdelab.simulator.mrubis.examples.selfhealing.MultiFaultSelfHealingScenario.CFLevel;
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;


/**
 * Feedback loop MAPE-K instrumentado para experimentación.
 *
 * Ejecuta el diseño factorial completo CON RÁFAGAS DE FALLAS.
 *
 * ESTRATEGIAS: STATIC, UTILITY_DRIVEN, SOLVER
 * NIVELES CF: CF1, CF2, CF3, CF5
 * CARGAS: 1, 10, 50, 100 fallas por ronda
 * REPETICIONES: 30 por celda
 *
 * v3: el cierre de ronda (medición de utilidad final, acumulada, y
 * registro de T_ajuste y T_estab) se realiza DESPUÉS de validateModel(),
 * capturando el estado consolidado del sistema. Se registra además
 * la cantidad de problemas pendientes que reporta el validador.
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class StateBasedMapeFeedbackLoop {


    public enum PlanningStrategy {
        STATIC,
        UTILITY_DRIVEN,
        SOLVER
    }


    private final PlanningStrategy strategy;

    public static final double FRACCION_AJUSTE = 0.6;

    public static final double FRACCION_ESTAB = 0.90;

    private final double umbralAjuste;

    private final double umbralEstab;

    private static final int CICLOS_ESTABLES = 3;


    private final int exceptionThreshold;

    private long t_falla = -1;

    private long t_ajuste = -1;

    private long t_estab = -1;

    private boolean ajusteRegistrado = false;

    private boolean estabRegistrada = false;

    private int ciclosEstables = 0;

    private double utilidadFinal = 0.0;

    private double utilidadAcumulada = 0.0;

    private double utilidadAcumuladaPostFalla = 0.0;

    private long tPlanNs = 0;

    private int planificaciones = 0;

    private int rondas = 0;

    private int rondasAjuste = -1;

    private int rondasEstab = -1;

    private long overheadNs = 0;

    // v3: problemas pendientes que reporta validateModel()
    private int problemasPendientesFinales = 0;


    private final MRubisSelfHealingUtilityFunction utilityFunction;


    public StateBasedMapeFeedbackLoop(int exceptionThreshold, PlanningStrategy strategy,
            double umbralAjuste, double umbralEstab) {

        this.exceptionThreshold = exceptionThreshold;

        this.strategy = strategy;

        this.umbralAjuste = umbralAjuste;

        this.umbralEstab = umbralEstab;

        this.utilityFunction =
                new MRubisSelfHealingUtilityFunction(
                        exceptionThreshold);
    }


    /**
     * Ejecuta la fase MAPE-K de la ronda.
     *
     * v3: YA NO mide utilidad final ni registra umbrales. Esas
     * operaciones se realizan en {@link #cerrarRonda(Architecture, int)}
     * después de que validateModel() haya limpiado el modelo.
     */
    public void run(Architecture architecture) {

        rondas++;

        // Utilidad inmediatamente después de inyectar la falla y ANTES
        // de cualquier acción del ciclo MAPE-K.
        long inicioMedicion = System.nanoTime();

        double utilidadPostFalla =
                utilityFunction.computeUtility(architecture);

        overheadNs += System.nanoTime() - inicioMedicion;

        utilidadAcumuladaPostFalla += utilidadPostFalla;

        Monitor monitor = new Monitor();

        if (monitor.monitor(architecture)) {

            Analyze analyze = new Analyze();
            analyze.analyze(architecture, exceptionThreshold);

            long inicioPlan = System.nanoTime();

            runPlanner(architecture);

            long finPlan = System.nanoTime();

            tPlanNs += (finPlan - inicioPlan);
            planificaciones++;

            Execute execute = new Execute();
            execute.execute(architecture);
        }
        // v3: la medición de utilidad final y el registro de umbrales
        // se han movido a cerrarRonda().
    }


    /**
     * Cierra la ronda: mide la utilidad del modelo consolidado (después
     * de validateModel()), acumula, registra umbrales y guarda los
     * problemas pendientes que reportó el validador.
     *
     * @param architecture          modelo tras validateModel()
     * @param problemasPendientes   lo que devolvió simulator.validateModel()
     */
    public void cerrarRonda(Architecture architecture, int problemasPendientes) {

        problemasPendientesFinales = problemasPendientes;

        long tiempoActual = System.nanoTime() - overheadNs;

        double utilidad = utilityFunction.computeUtility(architecture);

        utilidadFinal = utilidad;
        utilidadAcumulada += utilidad;

        registrarTAjuste(tiempoActual, utilidad);
        registrarTEstablecimiento(tiempoActual, utilidad);
    }


    private void registrarTAjuste(
            long tiempoActual,
            double utilidad) {

        if(!ajusteRegistrado
                && utilidad >= this.umbralAjuste
                && t_falla > 0) {

            t_ajuste = tiempoActual;
            ajusteRegistrado = true;
            rondasAjuste = rondas;
        }
    }


    private void registrarTEstablecimiento(
            long tiempoActual,
            double utilidad) {

        if(estabRegistrada || t_falla <= 0)
            return;

        if(utilidad >= this.umbralEstab){

            ciclosEstables++;

            if(ciclosEstables >= CICLOS_ESTABLES){

                t_estab = tiempoActual;
                estabRegistrada = true;
                rondasEstab = rondas;
            }

        }else{

            ciclosEstables = 0;
        }
    }


    private void runPlanner(Architecture architecture) {

        switch(this.strategy){

        case STATIC:

            new Plan()
                    .plan(architecture);

            break;

        case UTILITY_DRIVEN:

            new UtilityDrivenPlan(
                    exceptionThreshold)
                    .plan(architecture);

            break;

        case SOLVER:

            new SolverPlan()
                    .plan(architecture);

            break;
        }
    }


    public void registrarFalla(){

        t_falla = System.nanoTime();

        t_ajuste = -1;
        t_estab = -1;

        ajusteRegistrado = false;
        estabRegistrada = false;

        ciclosEstables = 0;

        utilidadFinal = 0.0;
        utilidadAcumulada = 0.0;
        utilidadAcumuladaPostFalla = 0.0;

        tPlanNs = 0;
        planificaciones = 0;
        rondas = 0;
        rondasAjuste = -1;
        rondasEstab = -1;
        overheadNs = 0;

        problemasPendientesFinales = 0;   // v3
    }


    public long getTAjusteMs(){

        if(t_ajuste < 0)
            return -1;

        return
          (t_ajuste - t_falla)
          /
          1_000_000;
    }


    public long getTEstabMs(){

        if(t_estab < 0)
            return -1;

        return
          (t_estab - t_falla)
          /
          1_000_000;
    }


    public double getUtilidadFinal(){
        return utilidadFinal;
    }

    public double getUtilidadAcumulada(){
        return utilidadAcumulada;
    }

    public double getUtilidadAcumuladaPostFalla(){
        return utilidadAcumuladaPostFalla;
    }

    public double getTPlanMs(){
        return tPlanNs / 1_000_000.0;
    }

    public int getPlanificaciones(){
        return planificaciones;
    }

    public int getRondas(){
        return rondas;
    }

    public int getRondasAjuste(){
        return rondasAjuste;
    }

    public int getRondasEstab(){
        return rondasEstab;
    }

    // v3
    public int getProblemasPendientesFinales(){
        return problemasPendientesFinales;
    }


    // ---------------------------------------------------------------
    // main() con el nuevo flujo de cierre de ronda
    // ---------------------------------------------------------------
    public static void main(String[] args)
            throws IOException {


        String modelURI =
                "./model/mRUBiS.comparch";

        int exceptionThreshold = 5;

        int minPipeSize = 4;

        int rounds = 20;

        int repeticiones = 30;

        long injectionDelayMS = 100;

        PlanningStrategy[] estrategias = {
                PlanningStrategy.STATIC,
                PlanningStrategy.UTILITY_DRIVEN,
                PlanningStrategy.SOLVER
        };

        CFLevel[] nivelesCF = {
                CFLevel.CF1,
                CFLevel.CF2,
                CFLevel.CF3,
                CFLevel.CF5
        };

        // Cargas (fallas simultáneas por ronda)
        int[] cargasPorRonda = { 1, 10, 50, 100 };


        String csvPath =
                "./results/experimento_factorial_v3_rafagas.csv";


        MetricsCollector metrics =
                new MetricsCollector(csvPath);


        for (PlanningStrategy strategy : estrategias) {

            for (CFLevel nivelCF : nivelesCF) {

                for (int fallasPorRonda : cargasPorRonda) {

                    for (int rep = 1; rep <= repeticiones; rep++) {


                        Architecture architecture =
                                SelfHealingConfig
                                .loadModel(modelURI);


                        UtilityFunction utilityFunction =
                                new MRubisSelfHealingUtilityFunction(
                                        exceptionThreshold);

                        double utilidadSana =
                                utilityFunction.computeUtility(
                                        architecture);

                        double umbralAjuste =
                                FRACCION_AJUSTE * utilidadSana;

                        double umbralEstab =
                                FRACCION_ESTAB * utilidadSana;


                        Scenario scenario =
                                new MultiFaultSelfHealingScenario(
                                        architecture,
                                        exceptionThreshold,
                                        nivelCF,
                                        fallasPorRonda);


                        Simulator simulator =
                                Simulator.FACTORY.createSimulator(
                                        architecture,
                                        utilityFunction,
                                        scenario,
                                        rounds,
                                        injectionDelayMS,
                                        false,
                                        Level.WARNING,
                                        false);


                        SelfHealingConfig.addValidators(
                                simulator,
                                exceptionThreshold,
                                minPipeSize);


                        StateBasedMapeFeedbackLoop loop =
                                new StateBasedMapeFeedbackLoop(
                                        exceptionThreshold,
                                        strategy,
                                        umbralAjuste,
                                        umbralEstab);


                        loop.registrarFalla();


                        do{

                            simulator.injectIssues();

                            loop.run(architecture);

                            // v3: medir después de validateModel()
                            int problemasPendientes =
                                    simulator.validateModel();

                            loop.cerrarRonda(architecture, problemasPendientes);

                        }while(!simulator.isSimulationCompleted());


                        metrics.record(
                                strategy.name(),
                                nivelCF.name(),
                                fallasPorRonda,
                                rep,
                                loop.getTAjusteMs(),
                                loop.getTEstabMs(),
                                loop.getRondasAjuste(),
                                loop.getRondasEstab(),
                                loop.getRondas(),
                                utilidadSana,
                                loop.getUtilidadFinal(),
                                loop.getUtilidadAcumulada(),
                                loop.getUtilidadAcumuladaPostFalla(),
                                loop.getTPlanMs(),
                                loop.getPlanificaciones(),
                                loop.getProblemasPendientesFinales()   // v3
                        );


                        LogManager.getLogManager().reset();


                        System.out.printf(
                                "[%s][%s][%d fallas][Rep %d] Ajuste=%d ms Estab=%d ms UtilFinal=%.4f UtilAcum=%.4f TPlan=%.2f ms Pend=%d%n",
                                strategy.name(),
                                nivelCF.name(),
                                fallasPorRonda,
                                rep,
                                loop.getTAjusteMs(),
                                loop.getTEstabMs(),
                                loop.getUtilidadFinal(),
                                loop.getUtilidadAcumulada(),
                                loop.getTPlanMs(),
                                loop.getProblemasPendientesFinales()
                        );
                    }
                }
            }
        }


        metrics.close();


        System.out.println(
                "CSV generado: "
                + csvPath);
    }
}