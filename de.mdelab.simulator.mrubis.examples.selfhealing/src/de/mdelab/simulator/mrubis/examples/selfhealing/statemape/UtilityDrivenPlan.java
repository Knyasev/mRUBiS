package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.util.ArrayList;
import java.util.Comparator;
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
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;

/**
 * Utility-Driven planning activity (versión 3, variante B).
 *
 * Prioriza las reparaciones por su RAZÓN utilidad estimada / costo estimado
 * (densidad de utilidad), en orden descendente. Es el criterio voraz estándar
 * del problema de la mochila y aproxima la selección que hace SolverPlan con
 * la misma utilidad y el mismo costo estimados.
 *
 * Con los valores estimados vigentes, el orden resultante es:
 *   CF2 (0.6 / 5 = 0.1200), CF1 (0.9 / 8 = 0.1125),
 *   CF5 (0.8 / 10 = 0.0800), CF3 (1.0 / 15 = 0.0667).
 *
 * La capacidad de reparación por ronda NO se aplica aquí: se aplica de forma
 * común a las tres estrategias en PresupuestoReparacion, después de la fase
 * Plan. El tope MAX_REPAIRS_PER_CYCLE es un límite técnico independiente.
 *
 * Las utilidades y los costos estimados son supuestos de diseño del autor y
 * coinciden con SolverPlan y PresupuestoReparacion; si se modifica uno, hay
 * que modificar los otros dos.
 *
 * Historial: en las versiones v1 y v2 esta estrategia ordenaba solo por la
 * utilidad estimada del tipo de falla, sin considerar el costo.
 *
 * Contribución técnica original – Wilson Israel Gonzalez Armijos, 2025
 */
public class UtilityDrivenPlan {

    private final MRubisSelfHealingUtilityFunction utilityFunction;
    private final int exceptionThreshold;

    // Maximum number of issues to plan per cycle.
    // Prevents the planner from being overwhelmed in high-severity faults.
    private static final int MAX_REPAIRS_PER_CYCLE = 50;

    public UtilityDrivenPlan(int exceptionThreshold) {
        this.exceptionThreshold = exceptionThreshold;
        this.utilityFunction    = new MRubisSelfHealingUtilityFunction(exceptionThreshold);
    }

    /**
     * Plans adaptations driven by the estimated utility per unit of cost.
     * Issues are sorted by that ratio (descending) before planning.
     *
     * @param architecture root element of the model
     */
    public void plan(Architecture architecture) {
        Annotations annotations = architecture.getAnnotations();
        List<Issue> issues = new ArrayList<>(annotations.getIssues());

        if (issues.isEmpty()) {
            return;
        }

        // 1. Score each issue by estimated utility gain per unit of cost
        List<ScoredIssue> scoredIssues = new ArrayList<>();
        for (Issue issue : issues) {
            double ratio = estimateUtilityPerCost(issue);
            scoredIssues.add(new ScoredIssue(issue, ratio));
        }

        // 2. Sort descending by ratio (stable: ties keep the detection order)
        scoredIssues.sort(Comparator.comparingDouble(ScoredIssue::getDelta).reversed());

        // 3. Plan only the top MAX_REPAIRS_PER_CYCLE issues
        int count = 0;
        for (ScoredIssue scoredIssue : scoredIssues) {
            if (count >= MAX_REPAIRS_PER_CYCLE) {
                break;
            }
            Issue issue        = scoredIssue.getIssue();
            AdaptationStrategy strategy = createStrategy(issue);
            if (strategy != null) {
                issue.getAssignedAdaptationStrategies().add(strategy);
                annotations.getAdaptationStrategies().add(strategy);
                count++;
            }
        }
    }

    /**
     * Estimated utility gain of repairing the issue (design assumption).
     * Same values as SolverPlan.getUtility().
     */
    static double estimatedUtility(String issueType) {
        switch (issueType) {
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
     * Estimated cost of repairing the issue (design assumption).
     * Same values as SolverPlan.getCost() and PresupuestoReparacion.costo().
     */
    static double estimatedCost(String issueType) {
        switch (issueType) {
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
     * Estimated utility per unit of cost (utility density) of an issue.
     */
    private double estimateUtilityPerCost(Issue issue) {
        String type = issue.getType();
        return estimatedUtility(type) / estimatedCost(type);
    }

    /**
     * Creates the appropriate AdaptationStrategy for a given issue,
     * using the same action mapping as the StaticPlanner.
     */
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

    // ---------------------------------------------------------------
    // Strategy factory helpers (same as Plan.java)
    // ---------------------------------------------------------------

    private AdaptationStrategy createLwRedeployComponent(Component component) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("LwRedeployComponent");
        InputParameter p = ComparchFactory.eINSTANCE.createInputParameter();
        p.setType("redeployComponent");
        p.setArchitecturalElement(component);
        s.getInputParameters().add(p);
        return s;
    }

    private AdaptationStrategy createRestartComponent(Component component) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("RestartComponent");
        InputParameter p = ComparchFactory.eINSTANCE.createInputParameter();
        p.setType("restartComponent");
        p.setArchitecturalElement(component);
        s.getInputParameters().add(p);
        return s;
    }

    private AdaptationStrategy createHwRedeployComponent(Tenant tenant,
                                                          ComponentType type) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("HwRedeployComponent");
        InputParameter p1 = ComparchFactory.eINSTANCE.createInputParameter();
        p1.setType("tenant");
        p1.setArchitecturalElement(tenant);
        s.getInputParameters().add(p1);
        InputParameter p2 = ComparchFactory.eINSTANCE.createInputParameter();
        p2.setType("componentType");
        p2.setArchitecturalElement(type);
        s.getInputParameters().add(p2);
        return s;
    }

    private AdaptationStrategy createRecreateConnector(RequiredInterface ri) {
        AdaptationStrategy s = ComparchFactory.eINSTANCE.createAdaptationStrategy();
        s.setType("RecreateConnector");
        InputParameter p = ComparchFactory.eINSTANCE.createInputParameter();
        p.setType("requiredInterface");
        p.setArchitecturalElement(ri);
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

    // ---------------------------------------------------------------
    // Inner class for scoring
    // ---------------------------------------------------------------

    private static class ScoredIssue {
        private final Issue  issue;
        private final double delta;

        ScoredIssue(Issue issue, double delta) {
            this.issue = issue;
            this.delta = delta;
        }

        Issue  getIssue() { return issue; }
        double getDelta() { return delta; }
    }
}