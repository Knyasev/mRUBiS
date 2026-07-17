package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.mdelab.comparch.AdaptationStrategy;
import de.mdelab.comparch.Annotations;
import de.mdelab.comparch.Architecture;
import de.mdelab.comparch.ComparchFactory;
import de.mdelab.comparch.Component;
import de.mdelab.comparch.ComponentState;
import de.mdelab.comparch.ComponentType;
import de.mdelab.comparch.Impact;
import de.mdelab.comparch.InputParameter;
import de.mdelab.comparch.Issue;
import de.mdelab.comparch.ProvidedInterface;
import de.mdelab.comparch.RequiredInterface;
import de.mdelab.comparch.Tenant;
import de.mdelab.simulator.mrubis.utility.MRubisSelfHealingUtilityFunction;

/**
 * Utility-Driven planning activity.
 * Prioritizes repair actions by their estimated utility gain,
 * processing higher-impact issues first.
 *
 * Contribution técnica original – Wilson Israel Gonzalez Armjos, 2025
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
     * Plans adaptations driven by estimated utility gain.
     * Issues are sorted by utility delta (descending) before planning.
     *
     * @param architecture root element of the model
     */
    public void plan(Architecture architecture) {
        Annotations annotations = architecture.getAnnotations();
        List<Issue> issues = new ArrayList<>(annotations.getIssues());

        if (issues.isEmpty()) {
            return;
        }

        // 1. Score each issue by estimated utility gain
        List<ScoredIssue> scoredIssues = new ArrayList<>();
        for (Issue issue : issues) {
            double delta = estimateUtilityGain(issue, architecture);
            scoredIssues.add(new ScoredIssue(issue, delta));
        }

        // 2. Sort descending by utility gain (highest impact first)
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
     * Estimates the utility gain of repairing a given issue.
     * Uses a heuristic based on issue type and affected element state.
     *
     * @param issue        the issue to evaluate
     * @param architecture the current architecture model
     * @return estimated utility delta [0.0 – 1.0]
     */
    private double estimateUtilityGain(Issue issue, Architecture architecture) {
        switch (issue.getType()) {
            case "CF1":
                // Unknown-state components block all dependent services  high impact
                return 0.9;
            case "CF2":
                // Exception-throwing components degrade quality but still serve  medium
                return 0.6;
            case "CF3":
                // Missing components block a tenant entirely very high impact
                return 1.0;
            case "CF5":
                // Broken connectors isolate components  high impact
                return 0.8;
            default:
                return 0.1;
        }
    }

    /**
     * Creates the appropriate AdaptationStrategy for a given issue,
     * using the same action mapping as the StaticPlanner but applied
     * only to high-priority issues selected by utility scoring.
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