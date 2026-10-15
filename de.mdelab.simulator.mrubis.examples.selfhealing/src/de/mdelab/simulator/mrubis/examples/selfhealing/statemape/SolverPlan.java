package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.util.ArrayList;
import java.util.List;

import de.mdelab.comparch.AdaptationStrategy;
import de.mdelab.comparch.Annotations;
import de.mdelab.comparch.Architecture;
import de.mdelab.comparch.ComparchFactory;
import de.mdelab.comparch.Component;
import de.mdelab.comparch.ComponentType;
import de.mdelab.comparch.Impact;
import de.mdelab.comparch.InputParameter;
import de.mdelab.comparch.Issue;
import de.mdelab.comparch.RequiredInterface;
import de.mdelab.comparch.Tenant;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.cplex.IloCplex;

/**
 * Solver-based planning activity using ILOG CPLEX.
 *
 * Formulates the repair planning problem as a 0/1 Integer Linear Program
 * (Knapsack problem):
 *
 * maximize (utility_i * x_i)
 * subject to (cost_i * x_i) <= budget
 * x_i in {0,1}
 *
 * Contribution:
 * Wilson Israel Gonzalez Armijos
 */
public class SolverPlan {

    /** Presupuesto por defecto si no se especifica uno en el constructor. */
    private static final double DEFAULT_BUDGET_MS = 40.0;

    /** Presupuesto configurable de reparación para esta instancia. */
    private final double budgetMs;

    /**
     * Constructor por defecto (usa el presupuesto de 40.0 ms).
     */
    public SolverPlan() {
        this(DEFAULT_BUDGET_MS);
    }

    /**
     * Constructor con presupuesto configurable por ronda/experimento.
     *
     * @param budgetMs capacidad o presupuesto de reparación en milisegundos.
     */
    public SolverPlan(double budgetMs) {
        this.budgetMs = budgetMs;
    }

    /**
     * Executes the planning stage.
     */
    public void plan(Architecture architecture) {

        Annotations annotations = architecture.getAnnotations();

        List<Issue> issues = new ArrayList<>(annotations.getIssues());

        if (issues.isEmpty()) {
            return;
        }

        int n = issues.size();

        double[] utility = new double[n];
        double[] cost = new double[n];

        for (int i = 0; i < n; i++) {
            utility[i] = getUtility(issues.get(i));
            cost[i] = getCost(issues.get(i));
        }

        boolean[] selected = solveILP(n, utility, cost, this.budgetMs);

        for (int i = 0; i < n; i++) {

            if (!selected[i]) {
                continue;
            }

            AdaptationStrategy strategy = createStrategy(issues.get(i));

            if (strategy != null) {
                issues.get(i).getAssignedAdaptationStrategies().add(strategy);
                annotations.getAdaptationStrategies().add(strategy);
            }
        }
    }

    /**
     * Solves the 0/1 Knapsack problem using CPLEX.
     */
    private boolean[] solveILP(int n, double[] utility, double[] cost, double budget) {

        boolean[] selected = new boolean[n];

        try {
            IloCplex cplex = new IloCplex();
            cplex.setOut(null);

            IloIntVar[] x = cplex.boolVarArray(n);
            IloLinearNumExpr objective = cplex.linearNumExpr();

            for (int i = 0; i < n; i++) {
                objective.addTerm(utility[i], x[i]);
            }

            cplex.addMaximize(objective);

            IloLinearNumExpr budgetExpr = cplex.linearNumExpr();

            for (int i = 0; i < n; i++) {
                budgetExpr.addTerm(cost[i], x[i]);
            }

            cplex.addLe(budgetExpr, budget);

            if (cplex.solve()) {
                double[] values = cplex.getValues(x);
                for (int i = 0; i < n; i++) {
                    selected[i] = values[i] > 0.5;
                }
            }

            cplex.end();

        } catch (IloException e) {
            System.err.println("[SolverPlan] CPLEX error: " + e.getMessage());
            System.err.println("Falling back to selecting every issue.");

            for (int i = 0; i < n; i++) {
                selected[i] = true;
            }
        }

        return selected;
    }

    /**
     * Estimated utility of repairing each issue.
     */
    private double getUtility(Issue issue) {
        switch (issue.getType()) {
            case "CF3":
                return 1.0;
            case "CF1":
                return 0.9;
            case "CF5":
                return 0.8;
            case "CF2":
                return 0.6;
            default:
                return 0.1;
        }
    }

    /**
     * Estimated repair cost (milliseconds). Coincide con PresupuestoReparacion.costo().
     */
    private double getCost(Issue issue) {
        switch (issue.getType()) {
            case "CF2":
                return 5.0;
            case "CF1":
                return 8.0;
            case "CF5":
                return 10.0;
            case "CF3":
                return 15.0;
            default:
                return 20.0;
        }
    }

    /**
     * Creates the corresponding adaptation strategy.
     */
    private AdaptationStrategy createStrategy(Issue issue) {

        List<Impact> impacts = issue.getImpacts();

        switch (issue.getType()) {
            case "CF1":
                return createLwRedeployComponent((Component) getImpactByType(impacts, "unknownComponent").getArchitecturalElement());

            case "CF2":
                return createRestartComponent((Component) getImpactByType(impacts, "affectedComponent").getArchitecturalElement());

            case "CF3":
                return createHwRedeployComponent(
                        (Tenant) getImpactByType(impacts, "affectedTenant").getArchitecturalElement(),
                        (ComponentType) getImpactByType(impacts, "affectedComponentType").getArchitecturalElement());

            case "CF5":
                return createRecreateConnector((RequiredInterface) getImpactByType(impacts, "affectedRequiredInterface").getArchitecturalElement());

            default:
                return null;
        }
    }

    private AdaptationStrategy createLwRedeployComponent(Component component) {
        AdaptationStrategy strategy = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        strategy.setType("LwRedeployComponent");

        InputParameter parameter = ComparchFactory.eINSTANCE.createInputParameter();
        parameter.setType("redeployComponent");
        parameter.setArchitecturalElement(component);

        strategy.getInputParameters().add(parameter);
        return strategy;
    }

    private AdaptationStrategy createRestartComponent(Component component) {
        AdaptationStrategy strategy = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        strategy.setType("RestartComponent");

        InputParameter parameter = ComparchFactory.eINSTANCE.createInputParameter();
        parameter.setType("restartComponent");
        parameter.setArchitecturalElement(component);

        strategy.getInputParameters().add(parameter);
        return strategy;
    }

    private AdaptationStrategy createHwRedeployComponent(Tenant tenant, ComponentType componentType) {
        AdaptationStrategy strategy = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        strategy.setType("HwRedeployComponent");

        InputParameter tenantParameter = ComparchFactory.eINSTANCE.createInputParameter();
        tenantParameter.setType("tenant");
        tenantParameter.setArchitecturalElement(tenant);
        strategy.getInputParameters().add(tenantParameter);

        InputParameter componentParameter = ComparchFactory.eINSTANCE.createInputParameter();
        componentParameter.setType("componentType");
        componentParameter.setArchitecturalElement(componentType);
        strategy.getInputParameters().add(componentParameter);

        return strategy;
    }

    private AdaptationStrategy createRecreateConnector(RequiredInterface requiredInterface) {
        AdaptationStrategy strategy = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        strategy.setType("RecreateConnector");

        InputParameter parameter = ComparchFactory.eINSTANCE.createInputParameter();
        parameter.setType("requiredInterface");
        parameter.setArchitecturalElement(requiredInterface);

        strategy.getInputParameters().add(parameter);
        return strategy;
    }

    private Impact getImpactByType(List<Impact> impacts, String type) {
        return impacts.stream()
                .filter(i -> i.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Impact not found: " + type));
    }
}