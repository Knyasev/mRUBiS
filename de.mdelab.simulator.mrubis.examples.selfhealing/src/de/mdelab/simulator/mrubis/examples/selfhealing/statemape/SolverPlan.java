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
 * Formulates the repair selection as an Integer Linear Program (ILP):
 *   maximize  sum(utility_i * x_i)
 *   subject to sum(cost_i * x_i) <= budget
 *              x_i in {0, 1}
 *
 * Contribution técnica original – Wilson Israel Gonzalez Armjos, 2025
 */
public class SolverPlan {

    // Maximum computational budget (ms) the solver may consume per cycle.
    // Acts as the ILP constraint — repairs with higher cost are deprioritized.
    private static final double BUDGET_MS = 500.0;

    /**
     * Plans adaptations by solving an ILP that maximizes utility
     * within a time budget constraint.
     *
     * @param architecture root element of the model
     */
    public void plan(Architecture architecture) {
        Annotations annotations = architecture.getAnnotations();
        List<Issue> issues = new ArrayList<>(annotations.getIssues());

        if (issues.isEmpty()) {
            return;
        }

        // Build utility and cost vectors
        int     n        = issues.size();
        double[] utility = new double[n];
        double[] cost    = new double[n];

        for (int i = 0; i < n; i++) {
            utility[i] = getUtility(issues.get(i));
            cost[i]    = getCost(issues.get(i));
        }

        // Solve ILP with CPLEX
        boolean[] selected = solveILP(n, utility, cost, BUDGET_MS);

        // Create adaptation strategies for selected issues
        for (int i = 0; i < n; i++) {
            if (selected[i]) {
                AdaptationStrategy strategy = createStrategy(issues.get(i));
                if (strategy != null) {
                    issues.get(i).getAssignedAdaptationStrategies().add(strategy);
                    annotations.getAdaptationStrategies().add(strategy);
                }
            }
        }
    }

    /**
     * Solves the 0/1 knapsack ILP using CPLEX.
     * maximize  sum(utility[i] * x[i])
     * subject to sum(cost[i] * x[i]) <= budget
     *
     * @return boolean array: selected[i]=true means issue i is selected
     */
    private boolean[] solveILP(int n, double[] utility,
                                double[] cost, double budget) {
        boolean[] selected = new boolean[n];
        try {
            IloCplex cplex = new IloCplex();
            cplex.setOut(null); // silence CPLEX output

            // Decision variables: x[i] in {0,1}
            IloIntVar[] x = cplex.boolVarArray(n);

            // Objective: maximize sum(utility[i] * x[i])
            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int i = 0; i < n; i++) {
                objective.addTerm(utility[i], x[i]);
            }
            cplex.addMaximize(objective);

            // Constraint: sum(cost[i] * x[i]) <= budget
            IloLinearNumExpr budgetExpr = cplex.linearNumExpr();
            for (int i = 0; i < n; i++) {
                budgetExpr.addTerm(cost[i], x[i]);
            }
            cplex.addLe(budgetExpr, budget);

            // Solve
            if (cplex.solve()) {
                double[] vals = cplex.getValues(x);
                for (int i = 0; i < n; i++) {
                    selected[i] = (vals[i] > 0.5); // rounding for binary vars
                }
            }
            cplex.end();

        } catch (IloException e) {
            // If CPLEX fails, fall back to selecting all issues (safe default)
            System.err.println("[SolverPlan] CPLEX error: " + e.getMessage()
                    + " — falling back to selecting all issues.");
            for (int i = 0; i < n; i++) {
                selected[i] = true;
            }
        }
        return selected;
    }

    /**
     * Utility value of repairing each issue type [0.0–1.0].
     * Higher = more beneficial to the system's accumulated utility.
     */
    private double getUtility(Issue issue) {
        switch (issue.getType()) {
            case "CF3": return 1.0;
            case "CF1": return 0.9;
            case "CF5": return 0.8;
            case "CF2": return 0.6;
            default:    return 0.1;
        }
    }

    /**
     * Estimated repair cost in ms for each issue type.
     * CF3 (heavy redeploy) is the most expensive; CF2 (restart) cheapest.
     */
    private double getCost(Issue issue) {
        switch (issue.getType()) {
            case "CF3": return 15.0;
            case "CF1": return 8.0;
            case "CF5": return 10.0;
            case "CF2": return 5.0;
            default:    return 20.0;
        }
    }

    // ---------------------------------------------------------------
    // Strategy factory helpers (identical to Plan.java)
    // ---------------------------------------------------------------

    private AdaptationStrategy createStrategy(Issue issue) {
        List<Impact> impacts = issue.getImpacts();
        switch (issue.getType()) {
            case "CF1": {
                Impact imp = getImpactByType(impacts, "unknownComponent");
                return createLwRedeployComponent(
                        (Component) imp.getArchitecturalElement());
            }
            case "CF2": {
                Impact imp = getImpactByType(impacts, "affectedComponent");
                return createRestartComponent(
                        (Component) imp.getArchitecturalElement());
            }
            case "CF3": {
                Impact impType   = getImpactByType(impacts, "affectedComponentType");
                Impact impTenant = getImpactByType(impacts, "affectedTenant");
                return createHwRedeployComponent(
                        (Tenant)        impTenant.getArchitecturalElement(),
                        (ComponentType) impType.getArchitecturalElement());
            }
            case "CF5": {
                Impact imp = getImpactByType(impacts, "affectedRequiredInterface");
                return createRecreateConnector(
                        (RequiredInterface) imp.getArchitecturalElement());
            }
            default:
                return null;
        }
    }

    private AdaptationStrategy createLwRedeployComponent(Component c) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("LwRedeployComponent");
        InputParameter p = ComparchFactory.eINSTANCE.createInputParameter();
        p.setType("redeployComponent"); p.setArchitecturalElement(c);
        s.getInputParameters().add(p);
        return s;
    }

    private AdaptationStrategy createRestartComponent(Component c) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("RestartComponent");
        InputParameter p = ComparchFactory.eINSTANCE.createInputParameter();
        p.setType("restartComponent"); p.setArchitecturalElement(c);
        s.getInputParameters().add(p);
        return s;
    }

    private AdaptationStrategy createHwRedeployComponent(Tenant t,
                                                          ComponentType ct) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("HwRedeployComponent");
        InputParameter p1 = ComparchFactory.eINSTANCE.createInputParameter();
        p1.setType("tenant"); p1.setArchitecturalElement(t);
        s.getInputParameters().add(p1);
        InputParameter p2 = ComparchFactory.eINSTANCE.createInputParameter();
        p2.setType("componentType"); p2.setArchitecturalElement(ct);
        s.getInputParameters().add(p2);
        return s;
    }

    private AdaptationStrategy createRecreateConnector(RequiredInterface ri) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("RecreateConnector");
        InputParameter p = ComparchFactory.eINSTANCE.createInputParameter();
        p.setType("requiredInterface"); p.setArchitecturalElement(ri);
        s.getInputParameters().add(p);
        return s;
    }

    private Impact getImpactByType(List<Impact> impacts, String type) {
        return impacts.stream()
                .filter(i -> i.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Impact type not found: " + type));
    }
}