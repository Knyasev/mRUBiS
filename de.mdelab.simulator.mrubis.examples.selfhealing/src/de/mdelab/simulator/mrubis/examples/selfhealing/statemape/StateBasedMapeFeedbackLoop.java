package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;

import de.mdelab.comparch.Architecture;
import de.mdelab.simulator.Simulator;

import de.mdelab.simulator.mrubis.examples.selfhealing.BurstScenario;
import de.mdelab.simulator.mrubis.examples.selfhealing.BurstScenario.Perfil;
import de.mdelab.simulator.mrubis.examples.selfhealing.SelfHealingConfig;
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;


/**
 * Feedback loop MAPE-K instrumentado para experimentacion.
 *
 * VERSION 3: escenarios de rafaga, presupuesto de reparación comun y metricas
 * sobre la perdida de utilidad.
 *
 * HISTORIAL
 *  v1  Disenio factorial 3 estrategias x CF1,CF2,CF3,CF5 x 30 repeticiones,
 *      una falla por ronda; T_ajuste y T_estab con umbrales sobre la
 *      utilidad total y utilidad medida dentro de run().
 *  v2  Se agregan utilidad acumulada, utilidad post-falla, tiempo de la fase
 *      Plan y conteo de rondas.
 *  v3  - La utilidad de cierre de ronda se mide DESPUES de validateModel()
 *        (en v1/v2 se media antes, y el efecto del reinicio de CF2, que lo
 *        aplica un validador, quedaba sin reflejarse).
 *      - Escenario de rafaga (BurstScenario): k fallas simultaneas en la
 *        ronda 1, con semilla (diseño pareado entre estrategias).
 *      - Presupuesto de reparacion comun (PresupuestoReparacion).
 *      - T_ajuste y T_estab se definen sobre la pérdida inicial de utilidad.
 *      - Traza por ronda y registro de inyecciones y de problemas de
 *        validacion.
 *
 * DEFINICIONES (v3)
 *  perdida_inicial = utilidad_sana - utilidad medida justo tras la rafaga.
 *  T_ajuste: tiempo hasta la primera ronda de cierre en que se ha
 *            recuperado al menos FRACCION_RECUPERACION_AJUSTE de la perdida
 *            inicial.
 *  T_estab:  tiempo hasta la ronda en que se completan CICLOS_ESTABLES rondas
 *            consecutivas con al menos FRACCION_RECUPERACION_ESTAB de la
 *            pérdida inicial recuperada.
 *  Si la rafaga no produce perdida (perdida_inicial <= 0), la repeticion se
 *  marca como no valida y ambos tiempos quedan en -1.
 *  recuperacion_completa: al terminar, utilidad igual a la sana y sin problemas
 *  de validacion. T_estab no la garantiza (basta con el 95 % de la perdida).
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


    /** Datos de una ronda, para la traza. */
    public static class RondaTraza {
        public int ronda;
        public double utilidadPostFalla;
        public double utilidadCierre;
        public int problemasValidacion;
        public int issuesDetectados;
        public int reparacionesEjecutadas;
        public double tPlanMs;
    }


    // Fracción de la pérdida inicial que debe recuperarse (umbrales de v3).
    // Se fijan ANTES de ejecutar el experimento y no se modifican después.
    public static final double FRACCION_RECUPERACION_AJUSTE = 0.50;

    public static final double FRACCION_RECUPERACION_ESTAB = 0.95;

    private static final int CICLOS_ESTABLES = 3;

    private static final double EPS = 1e-9;

    // Capacidad que se usa cuando el presupuesto se desactiva (capacidad <= 0).
    public static final double CAPACIDAD_SIN_LIMITE = 1.0e6;


    private final PlanningStrategy strategy;

    private final int exceptionThreshold;

    private final double utilidadSana;

    private final double capacidad;

    private final MRubisSelfHealingUtilityFunction utilityFunction;


    private long t_falla = -1;

    private long t_ajuste = -1;

    private long t_estab = -1;


    private boolean ajusteRegistrado = false;

    private boolean estabRegistrada = false;

    private int ciclosEstables = 0;


    private double utilidadPostFallaR1 = 0.0;

    private double perdidaInicial = 0.0;

    private double utilidadFinal = 0.0;

    private double utilidadAcumulada = 0.0;

    private double perdidaAcumulada = 0.0;

    private int problemasFinal = 0;

    private int problemasAcumulados = 0;

    private int reparacionesEjecutadas = 0;

    private int reparacionesDiferidas = 0;

    private long tPlanNs = 0;

    private int planificaciones = 0;

    private int rondas = 0;

    private int rondasAjuste = -1;

    private int rondasEstab = -1;

    // Tiempo gastado en mediciones de instrumentación; se descuenta de las
    // marcas de tiempo para no alterar T_ajuste ni T_estab.
    private long overheadNs = 0;


    // Datos de la ronda en curso (se completan en run() y en cerrarRonda()).
    private double rondaUtilidadPostFalla = 0.0;

    private int rondaIssuesDetectados = 0;

    private int rondaReparacionesEjecutadas = 0;

    private long rondaTPlanNs = 0;

    private final List<RondaTraza> traza = new ArrayList<RondaTraza>();



    /**
     * @param exceptionThreshold umbral de excepciones que define un CF2
     * @param strategy           estrategia de planificación
     * @param utilidadSana       utilidad del sistema antes de la ráfaga
     * @param capacidad          presupuesto de reparación por ronda; un valor
     *                           menor o igual que 0 desactiva el límite
     */
    public StateBasedMapeFeedbackLoop(int exceptionThreshold, PlanningStrategy strategy,
            double utilidadSana, double capacidad) {

        this.exceptionThreshold = exceptionThreshold;

        this.strategy = strategy;

        this.utilidadSana = utilidadSana;

        this.capacidad = capacidad;

        this.utilityFunction =
                new MRubisSelfHealingUtilityFunction(
                        exceptionThreshold);
    }



    /**
     * Primera parte de la ronda: el ciclo MAPE-K (Monitor, Analyze, Plan y
     * Execute). La utilidad de cierre se mide en {@link #cerrarRonda}, tras
     * simulator.validateModel().
     */
    public void run(Architecture architecture) {


        rondas++;


        // Utilidad inmediatamente después de inyectar y ANTES de actuar.
        long inicioMedicion =
                System.nanoTime();

        double utilidadPostFalla =
                utilityFunction.computeUtility(
                        architecture);

        overheadNs +=
                System.nanoTime() - inicioMedicion;

        if (rondas == 1) {

            utilidadPostFallaR1 = utilidadPostFalla;

            perdidaInicial = utilidadSana - utilidadPostFalla;
        }

        rondaUtilidadPostFalla = utilidadPostFalla;

        int detectadas = 0;

        int ejecutadas = 0;

        long tPlanRonda = 0;


        Monitor monitor = new Monitor();


        if (monitor.monitor(architecture)) {


            Analyze analyze = new Analyze();

            analyze.analyze(
                    architecture,
                    exceptionThreshold);


            detectadas =
                    architecture.getAnnotations().getIssues().size();


            long inicioPlan =
                    System.nanoTime();


            runPlanner(architecture);


            long finPlan =
                    System.nanoTime();


            tPlanRonda = finPlan - inicioPlan;

            tPlanNs += tPlanRonda;

            planificaciones++;


            ejecutadas =
                    architecture.getAnnotations().getAdaptationStrategies().size();


            Execute execute = new Execute();

            execute.execute(architecture);


        }


        reparacionesEjecutadas += ejecutadas;

        reparacionesDiferidas += (detectadas - ejecutadas);

        rondaIssuesDetectados = detectadas;

        rondaReparacionesEjecutadas = ejecutadas;

        rondaTPlanNs = tPlanRonda;

    }



    /**
     * Segunda parte de la ronda: cierre, tras simulator.validateModel().
     *
     * @param problemasValidacion valor que devuelve validateModel()
     */
    public void cerrarRonda(
            Architecture architecture,
            int problemasValidacion) {


        long tiempoActual =
                System.nanoTime() - overheadNs;


        double utilidad =
                utilityFunction.computeUtility(
                        architecture);


        utilidadFinal = utilidad;

        utilidadAcumulada += utilidad;

        perdidaAcumulada += (utilidadSana - utilidad);

        problemasFinal = problemasValidacion;

        problemasAcumulados += problemasValidacion;


        RondaTraza r = new RondaTraza();

        r.ronda = rondas;

        r.utilidadPostFalla = rondaUtilidadPostFalla;

        r.utilidadCierre = utilidad;

        r.problemasValidacion = problemasValidacion;

        r.issuesDetectados = rondaIssuesDetectados;

        r.reparacionesEjecutadas = rondaReparacionesEjecutadas;

        r.tPlanMs = rondaTPlanNs / 1_000_000.0;

        traza.add(r);


        registrarTAjuste(
                tiempoActual,
                utilidad);


        registrarTEstablecimiento(
                tiempoActual,
                utilidad);

    }



    /**
     * Indica si en la utilidad dada se ha recuperado al menos la fracción
     * indicada de la pérdida inicial.
     */
    private boolean recuperacionAlcanzada(
            double utilidad,
            double fraccion) {


        if (!esValida()) {

            return false;
        }


        double perdidaRestante =
                utilidadSana - utilidad;


        return perdidaRestante
                <= (1.0 - fraccion) * perdidaInicial + EPS;

    }



    private void registrarTAjuste(
            long tiempoActual,
            double utilidad) {


        if(!ajusteRegistrado
                && t_falla > 0
                && recuperacionAlcanzada(
                        utilidad,
                        FRACCION_RECUPERACION_AJUSTE)) {


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



        if(recuperacionAlcanzada(
                utilidad,
                FRACCION_RECUPERACION_ESTAB)){

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




    private void runPlanner(
            Architecture architecture) {


        boolean conLimite = capacidad > 0;

        double capacidadSolver =
                conLimite ? capacidad : CAPACIDAD_SIN_LIMITE;


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

            new SolverPlan(capacidadSolver)
                    .plan(architecture);

            break;
        }


        // Presupuesto común a las tres estrategias.
        if (conLimite) {

            PresupuestoReparacion.aplicar(
                    architecture,
                    capacidad);
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


        utilidadPostFallaR1 = 0.0;

        perdidaInicial = 0.0;

        utilidadFinal = 0.0;

        utilidadAcumulada = 0.0;

        perdidaAcumulada = 0.0;

        problemasFinal = 0;

        problemasAcumulados = 0;

        reparacionesEjecutadas = 0;

        reparacionesDiferidas = 0;

        tPlanNs = 0;

        planificaciones = 0;

        rondas = 0;

        rondasAjuste = -1;

        rondasEstab = -1;

        overheadNs = 0;

        traza.clear();

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



    /** true si la ráfaga produjo pérdida de utilidad (repetición analizable). */
    public boolean esValida(){

        return perdidaInicial > EPS;
    }

    public double getUtilidadPostFallaR1(){ return utilidadPostFallaR1; }

    public double getPerdidaInicial(){ return perdidaInicial; }

    public double getUtilidadFinal(){ return utilidadFinal; }

    public double getUtilidadAcumulada(){ return utilidadAcumulada; }

    public double getPerdidaAcumulada(){ return perdidaAcumulada; }

    /** Daño que queda al terminar: utilidad sana menos utilidad final. */
    public double getPerdidaResidual(){ return utilidadSana - utilidadFinal; }

    /**
     * true si al terminar la utilidad es la del sistema sano y no quedan
     * problemas de validación (recuperación completa).
     */
    public boolean esRecuperacionCompleta(){

        return esValida()
                && Math.abs(utilidadSana - utilidadFinal) <= 1e-6
                && problemasFinal == 0;
    }

    public int getProblemasFinal(){ return problemasFinal; }

    public int getProblemasAcumulados(){ return problemasAcumulados; }

    public int getReparacionesEjecutadas(){ return reparacionesEjecutadas; }

    public int getReparacionesDiferidas(){ return reparacionesDiferidas; }

    /** Tiempo acumulado en la fase Plan, en milisegundos. */
    public double getTPlanMs(){ return tPlanNs / 1_000_000.0; }

    public int getPlanificaciones(){ return planificaciones; }

    public int getRondas(){ return rondas; }

    public int getRondasAjuste(){ return rondasAjuste; }

    public int getRondasEstab(){ return rondasEstab; }

    public List<RondaTraza> getTraza(){ return traza; }




    // ---------------------------------------------------------------
    // main(): diseño factorial de la versión 3
    //   estrategia x escenario x tamaño de ráfaga x capacidad x repetición
    // ---------------------------------------------------------------

    // true: corrida piloto corta (1 repetición, tamaños grandes). false: diseño completo.
    private static final boolean PILOTO = false;

    // Repeticiones de calentamiento por estrategia, NO registradas, para que
    // la carga de clases, la compilación JIT y el primer uso de CPLEX no
    // contaminen las primeras mediciones.
    private static final boolean CALENTAMIENTO = true;

    private static final int REPETICIONES_CALENTAMIENTO = 2;

    private static final String MODEL_URI = "./model/mRUBiS.comparch";

    private static final int EXCEPTION_THRESHOLD = 5;

    private static final int MIN_PIPE_SIZE = 4;

    private static final int ROUNDS = 20;

    private static final long INJECTION_DELAY_MS = 100;


    /** Resultado de una repetición. */
    private static class Resultado {

        MetricsCollector.Fila fila;

        List<RondaTraza> traza;
    }


    /**
     * Tamaños de ráfaga por escenario. El modelo tiene 1 tenant y 8
     * componentes elegibles como objetivo, por lo que las ráfagas no pueden
     * superar ese número de objetivos distintos; CF2 admite como máximo 4
     * (interfaces usadas por componentes que siguen STARTED).
     */
    private static int[] tamanosPara(Perfil perfil) {

        if (PILOTO) {

            switch (perfil) {
            case CF2:
                return new int[] { 4 };
            case MIXTO:
                return new int[] { 8 };
            default:
                return new int[] { 6 };
            }
        }

        switch (perfil) {
        case CF2:
            return new int[] { 2, 4 };
        case MIXTO:
            return new int[] { 2, 4, 6, 8 };
        default:
            return new int[] { 2, 4, 6 };
        }
    }


    /** Misma semilla para las tres estrategias y para todas las capacidades. */
    private static long calcularSemilla(int rep, Perfil escenario, int tamano) {

        return 1_000_003L * rep
                + 10_007L * (escenario.ordinal() + 1)
                + tamano;
    }


    private static Resultado ejecutarRepeticion(
            PlanningStrategy strategy,
            Perfil escenario,
            int tamano,
            double capacidad,
            int rep,
            long semilla) {


        Architecture architecture =
                SelfHealingConfig
                .loadModel(MODEL_URI);


        MRubisSelfHealingUtilityFunction utilityFunction =
                new MRubisSelfHealingUtilityFunction(
                        EXCEPTION_THRESHOLD);


        double utilidadSana =
                utilityFunction.computeUtility(
                        architecture);


        BurstScenario scenario =
                new BurstScenario(
                        architecture,
                        EXCEPTION_THRESHOLD,
                        escenario,
                        tamano,
                        semilla);


        Simulator simulator =
                Simulator.FACTORY.createSimulator(
                        architecture,
                        utilityFunction,
                        scenario,
                        ROUNDS,
                        INJECTION_DELAY_MS,
                        false,
                        Level.WARNING,
                        false);


        SelfHealingConfig.addValidators(
                simulator,
                EXCEPTION_THRESHOLD,
                MIN_PIPE_SIZE);


        StateBasedMapeFeedbackLoop loop =
                new StateBasedMapeFeedbackLoop(
                        EXCEPTION_THRESHOLD,
                        strategy,
                        utilidadSana,
                        capacidad);


        // Marca el instante de la falla UNA sola vez.
        loop.registrarFalla();


        do{


            simulator.injectIssues();


            loop.run(architecture);


            int problemas =
                    simulator.validateModel();


            loop.cerrarRonda(
                    architecture,
                    problemas);


        }while(!simulator.isSimulationCompleted());


        MetricsCollector.Fila f =
                new MetricsCollector.Fila();

        f.estrategia = strategy.name();
        f.escenario = escenario.name();
        f.tamano = tamano;
        f.capacidad = capacidad;
        f.repeticion = rep;
        f.semilla = semilla;
        f.fallasPlanificadas = scenario.getFallasPlanificadas();
        f.inyeccionesExitosas = scenario.getInyeccionesExitosas();
        f.valida = loop.esValida() ? 1 : 0;
        f.utilidadSana = utilidadSana;
        f.utilidadPostFallaR1 = loop.getUtilidadPostFallaR1();
        f.perdidaInicial = loop.getPerdidaInicial();
        f.tAjusteMs = loop.getTAjusteMs();
        f.tEstabMs = loop.getTEstabMs();
        f.rondasAjuste = loop.getRondasAjuste();
        f.rondasEstab = loop.getRondasEstab();
        f.rondasTotal = loop.getRondas();
        f.utilidadFinal = loop.getUtilidadFinal();
        f.utilidadAcum = loop.getUtilidadAcumulada();
        f.perdidaAcum = loop.getPerdidaAcumulada();
        f.perdidaResidual = loop.getPerdidaResidual();
        f.recuperacionCompleta = loop.esRecuperacionCompleta() ? 1 : 0;
        f.problemasFinal = loop.getProblemasFinal();
        f.problemasAcum = loop.getProblemasAcumulados();
        f.reparacionesEjecutadas = loop.getReparacionesEjecutadas();
        f.reparacionesDiferidas = loop.getReparacionesDiferidas();
        f.tPlanMs = loop.getTPlanMs();
        f.nPlanificaciones = loop.getPlanificaciones();


        Resultado resultado = new Resultado();

        resultado.fila = f;

        resultado.traza = loop.getTraza();


        // Libera el FileHandler de Simulator.log.
        LogManager.getLogManager().reset();


        return resultado;
    }


    public static void main(String[] args)
            throws IOException {


        int repeticiones =
                PILOTO ? 1 : 30;


        // Para el análisis de sensibilidad agregar otra capacidad (p. ej. 20.0)
        // o ejecutar aparte; el nombre de los CSV incluye las capacidades.
        double[] capacidades =
                new double[] { 40.0 };


        PlanningStrategy[] estrategias = {
                PlanningStrategy.STATIC,
                PlanningStrategy.UTILITY_DRIVEN,
                PlanningStrategy.SOLVER
        };


        // CF4 no existe en el repositorio base.
        Perfil[] escenarios = {
                Perfil.CF1,
                Perfil.CF2,
                Perfil.CF3,
                Perfil.CF5,
                Perfil.MIXTO
        };


        // El sufijo identifica la configuración, para no mezclar corridas.
        String textoCapacidades = "";

        for (double c : capacidades) {

            textoCapacidades +=
                    (textoCapacidades.isEmpty() ? "" : "-")
                    + (int) c;
        }

        String sufijo =
                "_v3" + textoCapacidades
                + (PILOTO ? "_piloto" : "");


        // Los archivos nuevos no se pueden sobrescribir: si existen, falla.
        MetricsCollector metrics =
                new MetricsCollector("./results/experimento" + sufijo + ".csv");

        TrazaRondas trazaCsv =
                new TrazaRondas("./results/traza" + sufijo + ".csv");


        if (CALENTAMIENTO) {

            for (PlanningStrategy strategy : estrategias) {

                for (int w = 1; w <= REPETICIONES_CALENTAMIENTO; w++) {

                    ejecutarRepeticion(
                            strategy,
                            Perfil.MIXTO,
                            6,
                            capacidades[0],
                            0,
                            calcularSemilla(0, Perfil.MIXTO, 6));
                }
            }

            System.out.println("Calentamiento terminado (no registrado).");
        }


        for (Perfil escenario : escenarios) {

            for (int tamano : tamanosPara(escenario)) {

                for (double capacidad : capacidades) {

                    for (int rep = 1; rep <= repeticiones; rep++) {

                        long semilla =
                                calcularSemilla(rep, escenario, tamano);

                        for (int i = 0; i < estrategias.length; i++) {

                            // Se rota el orden de las estrategias en cada
                            // repetición para repartir efectos de orden.
                            PlanningStrategy strategy =
                                    estrategias[(i + rep) % estrategias.length];


                            Resultado r =
                                    ejecutarRepeticion(
                                            strategy,
                                            escenario,
                                            tamano,
                                            capacidad,
                                            rep,
                                            semilla);


                            MetricsCollector.Fila f = r.fila;

                            metrics.record(f);


                            for (RondaTraza ronda : r.traza) {

                                trazaCsv.record(
                                        strategy.name(),
                                        escenario.name(),
                                        tamano,
                                        capacidad,
                                        rep,
                                        ronda);
                            }


                            System.out.printf(
                                    "[v3][%s][%s][k=%d][cap=%.0f][rep %d] valida=%d iny=%d/%d perdida0=%.3f Tajuste=%d ms Testab=%d ms rondasEstab=%d completa=%d residual=%.3f problemas=%d TPlan=%.2f ms%n",
                                    strategy.name(),
                                    escenario.name(),
                                    tamano,
                                    capacidad,
                                    rep,
                                    f.valida,
                                    f.inyeccionesExitosas,
                                    f.fallasPlanificadas,
                                    f.perdidaInicial,
                                    f.tAjusteMs,
                                    f.tEstabMs,
                                    f.rondasEstab,
                                    f.recuperacionCompleta,
                                    f.perdidaResidual,
                                    f.problemasFinal,
                                    f.tPlanMs
                            );

                        }

                    }

                }

            }

        }



        metrics.close();

        trazaCsv.close();



        System.out.println(
                "CSV generados con sufijo: "
                + sufijo);

    }

}