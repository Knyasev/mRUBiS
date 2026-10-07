package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogManager;

import de.mdelab.comparch.Architecture;
import de.mdelab.simulator.Scenario;
import de.mdelab.simulator.Simulator;
import de.mdelab.simulator.UtilityFunction;

import de.mdelab.simulator.mrubis.examples.selfhealing.SelfHealingConfig;
import de.mdelab.simulator.mrubis.examples.selfhealing.SingleFaultSelfHealingScenario;
import de.mdelab.simulator.mrubis.examples.selfhealing.SingleFaultSelfHealingScenario.CFLevel;
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;


/**
 * Feedback loop MAPE-K instrumentado para experimentación.
 *
 * Ejecuta el diseño factorial completo:
 *
 * ESTRATEGIAS: STATIC, UTILITY_DRIVEN, SOLVER
 * NIVELES CF: CF1, CF2, CF3, CF5 (CF4 no disponible en el repositorio base)
 * REPETICIONES: 30 por celda
 *
 * midiendo:
 *
 * - T_ajuste
 * - T_estab
 * - utilidad acumulada
 *
 * mediante System.nanoTime().
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


    // ---------------------------------------------------------------
    // CAMBIO 1: la estrategia deja de ser un campo static final fijo
    // (que obligaba a recompilar para cambiar de estrategia) y pasa
    // a ser un campo de instancia, asignado por el constructor.
    // Esto permite que main() recorra las 3 estrategias en el mismo
    // proceso de ejecución.
    // ---------------------------------------------------------------
    private final PlanningStrategy strategy;


    // ---------------------------------------------------------------
    // CAMBIO 5: los umbrales dejan de ser valores absolutos fijos
    // (0.6 / 0.95) porque computeUtility() del modelo mRUBiS NO
    // devuelve un valor normalizado entre 0 y 1 (en la práctica
    // ronda los 27-32 puntos). Comparar esa utilidad contra 0.6 o
    // 0.95 hacía que el umbral de ajuste se cumpliera casi siempre
    // en la primera ronda, y el de establecimiento nunca reflejara
    // nada real. Ahora se pasan como FRACCIÓN de la utilidad del
    // sistema SANO (medida antes de inyectar la falla), calculada
    // en main() para cada repetición y entregada a esta instancia.
    // ---------------------------------------------------------------
    public static final double FRACCION_AJUSTE = 0.6;

    public static final double FRACCION_ESTAB = 0.95;

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



    private final MRubisSelfHealingUtilityFunction utilityFunction;



    // CAMBIO 2: el constructor ahora recibe también la estrategia
    // a utilizar en esta instancia del ciclo MAPE-K, y (CAMBIO 5)
    // los umbrales ya calculados como fracción de la utilidad sana
    // de ESTA repetición (distinta utilidad sana por CF/estrategia
    // si el modelo se recarga cada vez, así que se recalculan en
    // cada iteración de main()).
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
     * Ejecuta un ciclo completo MAPE-K.
     */
    public void run(Architecture architecture) {


        Monitor monitor = new Monitor();


        if (monitor.monitor(architecture)) {


            Analyze analyze = new Analyze();

            analyze.analyze(
                    architecture,
                    exceptionThreshold);



            long inicioPlan =
                    System.nanoTime();


            runPlanner(architecture);


            long finPlan =
                    System.nanoTime();


            Execute execute = new Execute();

            execute.execute(architecture);


        }



        long tiempoActual =
                System.nanoTime();



        double utilidad =
                utilityFunction.computeUtility(
                        architecture);



        utilidadFinal = utilidad;



        registrarTAjuste(
                tiempoActual,
                utilidad);



        registrarTEstablecimiento(
                tiempoActual,
                utilidad);

    }



    private void registrarTAjuste(
            long tiempoActual,
            double utilidad) {


        if(!ajusteRegistrado
                && utilidad >= this.umbralAjuste
                && t_falla > 0) {


            t_ajuste = tiempoActual;

            ajusteRegistrado = true;
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
            }

        }else{

            ciclosEstables = 0;
        }

    }




    // CAMBIO 3: runPlanner ahora usa el campo de instancia this.strategy
    // en lugar de la constante STATIC de la versión original.
    private void runPlanner(
            Architecture architecture) {


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


        t_falla =
                System.nanoTime();


        t_ajuste = -1;

        t_estab = -1;


        ajusteRegistrado = false;

        estabRegistrada = false;


        ciclosEstables = 0;


        utilidadFinal = 0.0;

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




    // ---------------------------------------------------------------
    // CAMBIO 4: main() ahora recorre el diseño factorial COMPLETO
    // (estrategia x nivel CF x repetición) en una sola ejecución,
    // y escribe todo en UN SOLO CSV combinado, tal como pide el
    // entregable de la Actividad 4 ("Base de datos experimental...
    // incluyendo estrategia, nivel CF, repetición, T_ajuste, T_estab,
    // utilidad acumulada").
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


        // CF4 excluido: el inyector CF4_Injector no está presente
        // en el repositorio base (según lo documentado previamente).
        CFLevel[] nivelesCF = {
                CFLevel.CF1,
                CFLevel.CF2,
                CFLevel.CF3,
                CFLevel.CF5
        };


        String csvPath =
                "./results/experimento_factorial_completo.csv";


        MetricsCollector metrics =
                new MetricsCollector(csvPath);



        for (PlanningStrategy strategy : estrategias) {

            for (CFLevel nivelCF : nivelesCF) {

                for (int rep = 1; rep <= repeticiones; rep++) {


                    Architecture architecture =
                            SelfHealingConfig
                            .loadModel(modelURI);



                    UtilityFunction utilityFunction =
                            new MRubisSelfHealingUtilityFunction(
                                    exceptionThreshold);


                    // CAMBIO 5: utilidad del sistema SANO, medida antes
                    // de inyectar cualquier falla.
                    
                    double utilidadSana =
                            utilityFunction.computeUtility(
                                    architecture);

                    double umbralAjuste =
                            FRACCION_AJUSTE * utilidadSana;

                    double umbralEstab =
                            FRACCION_ESTAB * utilidadSana;



                    // Escenario que inyecta ÚNICAMENTE el nivel "nivelCF"
                    // de esta iteración durante toda la ejecución, en vez
                    // de rotar entre los 4 tipos como hace el escenario
                    // original del repositorio base.
                    Scenario scenario =
                            new SingleFaultSelfHealingScenario(
                                    architecture,
                                    exceptionThreshold,
                                    nivelCF);



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



                    // CAMBIO 6 (fix crítico): registrarFalla() debe
                    // llamarse UNA sola vez por repetición, marcando el
                    // instante t_falla desde el cual se miden T_ajuste
                    // y T_estab. Si se llama dentro del do-while (como
                    // en la versión original), ciclosEstables se resetea
                    // a 0 en cada ronda y NUNCA puede llegar a
                    // CICLOS_ESTABLES=	 rondas consecutivas — por eso
                    // t_estab_ms salía -1 en las 360 filas.
                    loop.registrarFalla();


                    do{


                        simulator.injectIssues();


                        loop.run(architecture);



                        simulator.validateModel();



                    }while(!simulator.isSimulationCompleted());




                    metrics.record(
                            strategy.name(),
                            nivelCF.name(),
                            rep,
                            loop.getTAjusteMs(),
                            loop.getTEstabMs(),
                            loop.getUtilidadFinal()
                    );



                    // Libera el/los FileHandler que SimulatorImpl abrió
                    // sobre "./results/Simulator.log" en esta repetición.
                    // Sin esto, cada createSimulator() deja un lock
                    // abierto y, tras ~100 repeticiones, revienta con
                    // "Couldn't get lock for ./results/Simulator.log".
                    LogManager.getLogManager().reset();



                    System.out.printf(
                            "[%s][%s][Rep %d] Ajuste=%d ms Estab=%d ms Utilidad=%.4f%n",
                            strategy.name(),
                            nivelCF.name(),
                            rep,
                            loop.getTAjusteMs(),
                            loop.getTEstabMs(),
                            loop.getUtilidadFinal()
                    );

                }

            }

        }



        metrics.close();



        System.out.println(
                "CSV generado: "
                + csvPath);

    }

}