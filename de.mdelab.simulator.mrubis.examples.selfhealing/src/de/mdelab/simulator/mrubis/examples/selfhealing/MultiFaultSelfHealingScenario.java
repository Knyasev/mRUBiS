package de.mdelab.simulator.mrubis.examples.selfhealing;

import java.util.LinkedList;
import java.util.List;

import de.mdelab.comparch.ArchitecturalElement;
import de.mdelab.comparch.Architecture;
import de.mdelab.comparch.Component;
import de.mdelab.comparch.Connector;
import de.mdelab.comparch.ProvidedInterface;
import de.mdelab.comparch.RequiredInterface;
import de.mdelab.comparch.Tenant;
import de.mdelab.simulator.Injection;
import de.mdelab.simulator.Injector;
import de.mdelab.simulator.Scenario;
import de.mdelab.simulator.SimulatorUtil;
import de.mdelab.simulator.mrubis.MRubisNames;
import de.mdelab.simulator.mrubis.injectors.CF1_Injector;
import de.mdelab.simulator.mrubis.injectors.CF2_Injector;
import de.mdelab.simulator.mrubis.injectors.CF3_Injector;
import de.mdelab.simulator.mrubis.injectors.CF5_Injector;

/**
 * Escenario que inyecta RAFAGAS de fallas simultáneas por ronda.
 *
 * A diferencia de {@link SingleFaultSelfHealingScenario}, que inyecta
 * exactamente UNA falla del tipo {@code nivelCF} en cada ronda, esta clase
 * inyecta {@code fallasPorRonda} fallas simultáneas del mismo tipo en la
 * misma ronda. Esto genera la escasez de recursos necesaria para que la
 * priorización de los planificadores se manifieste, siguiendo el diseño
 * experimental de Ghahremani et al. (2020).
 *
 * Los targets se seleccionan aleatoriamente SIN repetición dentro de una
 * misma ráfaga, para evitar inyectar dos veces la misma falla.
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class MultiFaultSelfHealingScenario implements Scenario {

    public enum CFLevel {
        CF1,
        CF2,
        CF3,
        CF5
    }

    private final Architecture architecture;
    private final int exceptionThreshold;
    private final CFLevel nivelCF;
    private final int fallasPorRonda;

    /**
     * @param architecture        el modelo raíz
     * @param exceptionThreshold  umbral de excepciones que provoca un CF2
     * @param nivelCF             el tipo de falla crítica inyectada
     * @param fallasPorRonda      número de fallas simultáneas por ronda
     */
    public MultiFaultSelfHealingScenario(Architecture architecture,
                                          int exceptionThreshold,
                                          CFLevel nivelCF,
                                          int fallasPorRonda) {
        this.architecture = architecture;
        this.exceptionThreshold = exceptionThreshold;
        this.nivelCF = nivelCF;
        this.fallasPorRonda = fallasPorRonda;
    }

    @Override
    public List<Injection<? extends ArchitecturalElement>> getInjections(int currentRound) {
        List<Injection<? extends ArchitecturalElement>> injections = new LinkedList<>();

        switch (this.nivelCF) {
        case CF1:
            inyectarComponentes(injections, fallasPorRonda, new CF1_Injector());
            break;
        case CF2:
            inyectarInterfaces(injections, fallasPorRonda);
            break;
        case CF3:
            inyectarComponentes(injections, fallasPorRonda, new CF3_Injector());
            break;
        case CF5:
            inyectarConectores(injections, fallasPorRonda);
            break;
        default:
            break;
        }

        return injections;
    }

    private void inyectarComponentes(
            List<Injection<? extends ArchitecturalElement>> injections,
            int n, Injector<Component> injector) {

        List<Component> candidatos = listarComponentesCandidatos();
        int aInyectar = Math.min(n, candidatos.size());

        for (int i = 0; i < aInyectar; i++) {
            Component c = candidatos.remove(
                    SimulatorUtil.randInt(0, candidatos.size() - 1));
            injections.add(new Injection<Component>(c, injector));
        }
    }

    private void inyectarInterfaces(
            List<Injection<? extends ArchitecturalElement>> injections, int n) {

        List<ProvidedInterface> candidatas = listarInterfacesUsadas();
        int aInyectar = Math.min(n, candidatas.size());

        for (int i = 0; i < aInyectar; i++) {
            ProvidedInterface pi = candidatas.remove(
                    SimulatorUtil.randInt(0, candidatas.size() - 1));
            injections.add(new Injection<ProvidedInterface>(pi,
                    new CF2_Injector(this.exceptionThreshold)));
        }
    }

    private void inyectarConectores(
            List<Injection<? extends ArchitecturalElement>> injections, int n) {

        List<Connector> candidatos = listarConectores();
        int aInyectar = Math.min(n, candidatos.size());

        for (int i = 0; i < aInyectar; i++) {
            Connector c = candidatos.remove(
                    SimulatorUtil.randInt(0, candidatos.size() - 1));
            injections.add(new Injection<Connector>(c, new CF5_Injector()));
        }
    }

    // ---------------------------------------------------------------
    // Listado de candidatos (evita duplicados dentro de una ráfaga)
    // ---------------------------------------------------------------

    private List<Component> listarComponentesCandidatos() {
        List<Component> resultado = new LinkedList<>();
        for (Tenant t : this.architecture.getTenants()) {
            for (Component c : t.getComponents()) {
                boolean esFiltro = false;
                for (ProvidedInterface pi : c.getProvidedInterfaces()) {
                    if (pi.getType().getFqName()
                            .equals(MRubisNames.itemFilterService_InterfaceType_FqName)) {
                        esFiltro = true;
                        break;
                    }
                }
                if (!esFiltro) {
                    resultado.add(c);
                }
            }
        }
        return resultado;
    }

    private List<ProvidedInterface> listarInterfacesUsadas() {
        List<ProvidedInterface> resultado = new LinkedList<>();
        for (Component c : listarComponentesCandidatos()) {
            for (ProvidedInterface pi : c.getProvidedInterfaces()) {
                if (!pi.getConnectors().isEmpty()) {
                    resultado.add(pi);
                }
            }
        }
        return resultado;
    }

    private List<Connector> listarConectores() {
        List<Connector> resultado = new LinkedList<>();
        for (Component c : listarComponentesCandidatos()) {
            for (RequiredInterface ri : c.getRequiredInterfaces()) {
                Connector con = ri.getConnector();
                if (con != null && con.getTarget() != null) {
                    resultado.add(con);
                }
            }
        }
        return resultado;
    }

    @Override
    public void notifyAboutSuccess(List<Injection<? extends ArchitecturalElement>> injections) {
        // No requiere acción.
    }
}