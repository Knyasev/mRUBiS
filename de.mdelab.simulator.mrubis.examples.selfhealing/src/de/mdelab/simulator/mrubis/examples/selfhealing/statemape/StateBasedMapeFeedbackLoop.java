package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.IOException;
import java.util.logging.Level;

import de.mdelab.comparch.Architecture;
import de.mdelab.simulator.Scenario;
import de.mdelab.simulator.Simulator;
import de.mdelab.simulator.UtilityFunction;

import de.mdelab.simulator.mrubis.examples.selfhealing.SelfHealingConfig;
import de.mdelab.simulator.mrubis.examples.selfhealing.SelfHealingScenario;
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;


/**
 * Feedback loop MAPE-K instrumentado para experimentación.
 *
 * Permite comparar:
 *
 * STATIC
 * UTILITY_DRIVEN
 * SOLVER
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


    /**
     * Cambiar para ejecutar cada estrategia.
     */
    private static final PlanningStrategy STRATEGY =
            PlanningStrategy.STATIC;



    private static final double UMBRAL_AJUSTE = 0.6;

    private static final double UMBRAL_ESTAB = 0.85;

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



    public StateBasedMapeFeedbackLoop(int exceptionThreshold) {

        this.exceptionThreshold = exceptionThreshold;

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
                && utilidad >= UMBRAL_AJUSTE
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



        if(utilidad >= UMBRAL_ESTAB){

            ciclosEstables++;


            if(ciclosEstables >= CICLOS_ESTABLES){

                t_estab = tiempoActual;

                estabRegistrada = true;
            }

        }else{

            ciclosEstables = 0;
        }

    }




    private void runPlanner(
            Architecture architecture) {


        switch(STRATEGY){


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




    public static void main(String[] args)
            throws IOException {



        String modelURI =
                "./model/mRUBiS.comparch";


        int exceptionThreshold = 5;


        int minPipeSize = 4;


        int rounds = 20;


        int repeticiones = 30;


        long injectionDelayMS = 100;



        String nivelCF = "CF1";



        String csvPath =
                "./results/experimento_"
                + STRATEGY.name()
                + "_"
                + nivelCF
                + ".csv";



        MetricsCollector metrics =
                new MetricsCollector(csvPath);



        for(int rep=1;
            rep<=repeticiones;
            rep++){



            Architecture architecture =
                    SelfHealingConfig
                    .loadModel(modelURI);



            UtilityFunction utilityFunction =
                    new MRubisSelfHealingUtilityFunction(
                            exceptionThreshold);



            Scenario scenario =
                    new SelfHealingScenario(
                            architecture,
                            exceptionThreshold);



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
                            exceptionThreshold);



            do{


                simulator.injectIssues();


                loop.registrarFalla();


                loop.run(architecture);



                simulator.validateModel();



            }while(!simulator.isSimulationCompleted());




            metrics.record(
                    STRATEGY.name(),
                    nivelCF,
                    rep,
                    loop.getTAjusteMs(),
                    loop.getTEstabMs(),
                    loop.getUtilidadFinal()
            );



            System.out.printf(
                    "[Rep %d] Ajuste=%d ms Estab=%d ms Utilidad=%.4f%n",
                    rep,
                    loop.getTAjusteMs(),
                    loop.getTEstabMs(),
                    loop.getUtilidadFinal()
            );

        }



        metrics.close();



        System.out.println(
                "CSV generado: "
                + csvPath);

    }

}