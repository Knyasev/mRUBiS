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
import de.mdelab.simulator.Scenario;
import de.mdelab.simulator.SimulatorUtil;
import de.mdelab.simulator.mrubis.MRubisNames;
import de.mdelab.simulator.mrubis.injectors.CF1_Injector;
import de.mdelab.simulator.mrubis.injectors.CF2_Injector;
import de.mdelab.simulator.mrubis.injectors.CF3_Injector;
import de.mdelab.simulator.mrubis.injectors.CF5_Injector;

/**
 * Escenario que, a diferencia de {@link SelfHealingScenario} (que rota
 * ciclicamente entre CF1, CF2, CF3 y CF5 según la ronda), inyecta
 * SIEMPRE el mismo nivel de falla crítica en todas las rondas de la
 * ejecución. Esto es necesario para poder aislar el efecto de cada
 * nivel de severidad (CF1-CF5) en el diseño experimental factorial
 * (estrategia x nivel CF x repetición) de la Actividad 4.
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class SingleFaultSelfHealingScenario implements Scenario {

	public enum CFLevel {
		CF1,
		CF2,
		CF3,
		CF5
	}

	private Architecture architecture;
	private int exceptionThreshold;
	private CFLevel nivelCF;

	/**
	 * @param architecture        el modelo raíz
	 * @param exceptionThreshold  umbral de excepciones que provoca un CF2
	 * @param nivelCF             el único tipo de falla crítica que este
	 *                            escenario inyectará en todas las rondas
	 */
	public SingleFaultSelfHealingScenario(Architecture architecture, int exceptionThreshold, CFLevel nivelCF) {
		this.architecture = architecture;
		this.exceptionThreshold = exceptionThreshold;
		this.nivelCF = nivelCF;
	}

	/**
	 * @see Scenario#getInjections(int)
	 */
	@Override
	public List<Injection<? extends ArchitecturalElement>> getInjections(int currentRound) {
		List<Injection<? extends ArchitecturalElement>> injections = new LinkedList<Injection<? extends ArchitecturalElement>>();

		switch (this.nivelCF) {
		case CF1:
			Injection<Component> cf1 = new Injection<Component>(this.selectRandomComponent(), new CF1_Injector());
			injections.add(cf1);
			break;
		case CF2:
			Injection<ProvidedInterface> cf2 = new Injection<ProvidedInterface>(
					this.selectRandomUsedProvidedInterface(), new CF2_Injector(this.exceptionThreshold));
			injections.add(cf2);
			break;
		case CF3:
			Injection<Component> cf3 = new Injection<Component>(this.selectRandomComponent(), new CF3_Injector());
			injections.add(cf3);
			break;
		case CF5:
			Injection<Connector> cf5 = new Injection<Connector>(this.selectRandomConnector(), new CF5_Injector());
			injections.add(cf5);
			break;
		default:
			break;
		}

		return injections;
	}

	/**
	 * @see Scenario#notifyAboutSuccess(java.util.List)
	 */
	@Override
	public void notifyAboutSuccess(List<Injection<? extends ArchitecturalElement>> injections) {
		// puede usarse para ajustar el escenario según el éxito de las
		// inyecciones intentadas hasta el momento; no se requiere aquí.
	}

	/**
	 * @return un {@link Component} aleatorio del modelo, excluyendo los
	 *         componentes de tipo filtro (idéntico al comportamiento de
	 *         {@link SelfHealingScenario}).
	 */
	private Component selectRandomComponent() {
		Tenant tenant = this.selectRandomTenant();
		if (tenant != null) {
			int tries = 0;
			Component component = null;
			while (component == null && tries < 50) {
				int componentNumber = SimulatorUtil.randInt(0, tenant.getComponents().size() - 1);
				component = tenant.getComponents().get(componentNumber);
				for (ProvidedInterface providedInterface : component.getProvidedInterfaces()) {
					if (providedInterface.getType().getFqName()
							.equals(MRubisNames.itemFilterService_InterfaceType_FqName)) {
						component = null;
						break;
					}
				}
				tries++;
			}
			return component;
		} else {
			return null;
		}
	}

	/**
	 * @return un {@link Tenant} aleatorio del modelo.
	 */
	private Tenant selectRandomTenant() {
		List<Tenant> tenants = this.architecture.getTenants();
		if (tenants.size() > 0) {
			int tenantNumber = SimulatorUtil.randInt(0, tenants.size() - 1);
			Tenant tenant = tenants.get(tenantNumber);
			return tenant;
		} else {
			return null;
		}
	}

	/**
	 * @return una {@link ProvidedInterface} aleatoria que esté conectada
	 *         mediante un connector a una interfaz requerida.
	 */
	private ProvidedInterface selectRandomUsedProvidedInterface() {
		List<ProvidedInterface> usedInterfaces = new LinkedList<ProvidedInterface>();
		int tries = 0;

		while (usedInterfaces.size() == 0 || tries < 50) {
			tries++;
			Component component = this.selectRandomComponent();
			if (component != null) {
				for (ProvidedInterface pi : component.getProvidedInterfaces()) {
					if (pi.getConnectors().size() > 0) {
						usedInterfaces.add(pi);
					}
				}
			} else {
				break;
			}
		}
		if (usedInterfaces.size() > 0) {
			return usedInterfaces.get(SimulatorUtil.randInt(0, usedInterfaces.size() - 1));
		} else {
			return null;
		}
	}

	/**
	 * @return un {@link Connector} aleatorio que conecte una interfaz
	 *         requerida y una provista.
	 */
	private Connector selectRandomConnector() {
		Connector connector = null;
		int tries = 0;
		while (connector == null && tries < 50) {
			tries++;
			Component component = this.selectRandomComponent();
			if (component != null) {
				for (RequiredInterface ri : component.getRequiredInterfaces()) {
					Connector c = ri.getConnector();
					if (c != null && c.getTarget() != null) {
						connector = c;
						break;
					}
				}
			} else {
				break;
			}
		}
		return connector;
	}

}