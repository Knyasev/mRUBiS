package de.mdelab.simulator.mrubis.examples.selfhealing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import de.mdelab.comparch.ArchitecturalElement;
import de.mdelab.comparch.Architecture;
import de.mdelab.comparch.Component;
import de.mdelab.comparch.ComponentState;
import de.mdelab.comparch.Connector;
import de.mdelab.comparch.ProvidedInterface;
import de.mdelab.comparch.RequiredInterface;
import de.mdelab.comparch.Tenant;
import de.mdelab.simulator.Injection;
import de.mdelab.simulator.Injector;
import de.mdelab.simulator.Scenario;
import de.mdelab.simulator.mrubis.MRubisNames;
import de.mdelab.simulator.mrubis.injectors.CF1_Injector;
import de.mdelab.simulator.mrubis.injectors.CF2_Injector;
import de.mdelab.simulator.mrubis.injectors.CF3_Injector;
import de.mdelab.simulator.mrubis.injectors.CF5_Injector;

/**
 * Escenario de RÁFAGA (versión 3 del experimento).
 *
 * Inyecta, en la primera ronda, una ráfaga de {@code tamano} fallas
 * simultáneas y no inyecta nada en las rondas siguientes. A partir de ahí el
 * ciclo MAPE-K debe recuperar el sistema, y la trayectoria de recuperación
 * (utilidad ronda a ronda) es lo que se mide.
 *
 * Perfiles:
 *  - CF1, CF2, CF3, CF5: las {@code tamano} fallas son del mismo tipo.
 *  - MIXTO: los tipos se reparten por turnos en el orden CF3, CF1, CF5, CF2
 *    (p. ej. tamaño 6: 2 CF3, 2 CF1, 1 CF5 y 1 CF2).
 *
 * Reproducibilidad: el azar proviene de un {@link Random} propio con semilla
 * explícita, no de SimulatorUtil. Con la misma semilla y el mismo modelo, las
 * tres estrategias reciben exactamente las mismas fallas (diseño pareado).
 * El único azar no controlado es el de CF2_Injector (número de excepciones
 * entre umbral+1 y 2*umbral), que no cambia la utilidad.
 *
 * Elección de objetivos (para que la ráfaga no se anule a sí misma):
 *  - Todos los objetivos son componentes distintos entre sí, en estado
 *    STARTED y que no sean filtros (mismo criterio que SelfHealingScenario).
 *  - Una falla CF2 solo se asigna a una interfaz usada por un componente que
 *    seguirá STARTED (no afectado por CF1 ni CF3).
 *  - Un CF5 solo se asigna a un conector cuyos dos extremos seguirán STARTED.
 *  - Orden de inyección: CF2, CF5, CF1, CF3.
 *
 * Registro: cada inyección se envuelve para contar intentos y éxitos reales,
 * ya que un inyector devuelve false si su condición no se cumple.
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public class BurstScenario implements Scenario {

	/** Perfil de la ráfaga. */
	public enum Perfil {
		CF1, CF2, CF3, CF5, MIXTO
	}

	private enum TipoFalla {
		CF1, CF2, CF3, CF5
	}

	// Orden de reparto de tipos en el perfil MIXTO.
	private static final TipoFalla[] ORDEN_MIXTO = { TipoFalla.CF3, TipoFalla.CF1, TipoFalla.CF5, TipoFalla.CF2 };

	private final Architecture architecture;
	private final int exceptionThreshold;
	private final Perfil perfil;
	private final int tamano;
	private final long semilla;
	private final Random rnd;

	private boolean rafagaGenerada = false;
	private int fallasPlanificadas = 0;
	private int inyeccionesIntentadas = 0;
	private int inyeccionesExitosas = 0;

	/**
	 * @param architecture
	 *            raíz del modelo
	 * @param exceptionThreshold
	 *            umbral de excepciones que define un CF2
	 * @param perfil
	 *            perfil de la ráfaga
	 * @param tamano
	 *            número de fallas de la ráfaga
	 * @param semilla
	 *            semilla del generador aleatorio de este escenario
	 */
	public BurstScenario(Architecture architecture, int exceptionThreshold, Perfil perfil, int tamano, long semilla) {
		this.architecture = architecture;
		this.exceptionThreshold = exceptionThreshold;
		this.perfil = perfil;
		this.tamano = tamano;
		this.semilla = semilla;
		this.rnd = new Random(semilla);
	}

	/**
	 * @see Scenario#getInjections(int)
	 */
	@Override
	public List<Injection<? extends ArchitecturalElement>> getInjections(int currentRound) {
		List<Injection<? extends ArchitecturalElement>> lista = new LinkedList<Injection<? extends ArchitecturalElement>>();

		if (this.rafagaGenerada) {
			// la ráfaga se inyecta una sola vez
			return lista;
		}
		this.rafagaGenerada = true;

		List<TipoFalla> tipos = this.tiposDeLaRafaga();

		// 1. conjunto de componentes candidatos, mezclado con la semilla
		List<Component> pool = new ArrayList<Component>();
		for (Tenant tenant : this.architecture.getTenants()) {
			for (Component component : tenant.getComponents()) {
				if (component.getState() == ComponentState.STARTED && !this.esFiltro(component)) {
					pool.add(component);
				}
			}
		}
		Collections.shuffle(pool, this.rnd);

		Set<Component> usados = new HashSet<Component>();
		Set<Component> perdidos = new HashSet<Component>();

		// 2. objetivos de CF3 y CF1 (componentes que dejarán de estar STARTED)
		List<Component> objetivosCF3 = new ArrayList<Component>();
		List<Component> objetivosCF1 = new ArrayList<Component>();
		for (TipoFalla tipo : tipos) {
			if (tipo == TipoFalla.CF3 || tipo == TipoFalla.CF1) {
				Component c = this.siguienteLibre(pool, usados);
				if (c != null) {
					usados.add(c);
					perdidos.add(c);
					if (tipo == TipoFalla.CF3) {
						objetivosCF3.add(c);
					} else {
						objetivosCF1.add(c);
					}
				}
			}
		}

		// 3. objetivos de CF2 (interfaces usadas por componentes que seguirán STARTED)
		List<ProvidedInterface> objetivosCF2 = new ArrayList<ProvidedInterface>();
		for (TipoFalla tipo : tipos) {
			if (tipo == TipoFalla.CF2) {
				ProvidedInterface pi = this.siguienteInterfazCF2(pool, usados, perdidos);
				if (pi != null) {
					usados.add(pi.getComponent());
					objetivosCF2.add(pi);
				}
			}
		}

		// 4. objetivos de CF5 (conectores cuyos extremos seguirán STARTED)
		int nCF5 = 0;
		for (TipoFalla tipo : tipos) {
			if (tipo == TipoFalla.CF5) {
				nCF5++;
			}
		}
		List<Connector> objetivosCF5 = this.elegirConectores(pool, perdidos, nCF5);

		// 5. ráfaga en el orden CF2, CF5, CF1, CF3
		for (ProvidedInterface pi : objetivosCF2) {
			lista.add(new Injection<ProvidedInterface>(pi,
					new RegistroInyector<ProvidedInterface>(new CF2_Injector(this.exceptionThreshold))));
		}
		for (Connector conn : objetivosCF5) {
			lista.add(new Injection<Connector>(conn, new RegistroInyector<Connector>(new CF5_Injector())));
		}
		for (Component c : objetivosCF1) {
			lista.add(new Injection<Component>(c, new RegistroInyector<Component>(new CF1_Injector())));
		}
		for (Component c : objetivosCF3) {
			lista.add(new Injection<Component>(c, new RegistroInyector<Component>(new CF3_Injector())));
		}

		this.fallasPlanificadas = lista.size();
		return lista;
	}

	/**
	 * @see Scenario#notifyAboutSuccess(java.util.List)
	 */
	@Override
	public void notifyAboutSuccess(List<Injection<? extends ArchitecturalElement>> injections) {
		// el conteo de éxitos se hace en RegistroInyector
	}

	/** Número de fallas que el escenario logró planificar (puede ser menor que el tamaño pedido). */
	public int getFallasPlanificadas() {
		return this.fallasPlanificadas;
	}

	/** Número de inyecciones ejecutadas por el simulador. */
	public int getInyeccionesIntentadas() {
		return this.inyeccionesIntentadas;
	}

	/** Número de inyecciones que tuvieron efecto (el inyector devolvió true). */
	public int getInyeccionesExitosas() {
		return this.inyeccionesExitosas;
	}

	public long getSemilla() {
		return this.semilla;
	}

	// ------------------------------------------------------------------

	private List<TipoFalla> tiposDeLaRafaga() {
		List<TipoFalla> tipos = new ArrayList<TipoFalla>();
		if (this.perfil == Perfil.MIXTO) {
			for (int i = 0; i < this.tamano; i++) {
				tipos.add(ORDEN_MIXTO[i % ORDEN_MIXTO.length]);
			}
		} else {
			TipoFalla tipo = TipoFalla.valueOf(this.perfil.name());
			for (int i = 0; i < this.tamano; i++) {
				tipos.add(tipo);
			}
		}
		return tipos;
	}

	private Component siguienteLibre(List<Component> pool, Set<Component> usados) {
		for (Component c : pool) {
			if (!usados.contains(c)) {
				return c;
			}
		}
		return null;
	}

	private ProvidedInterface siguienteInterfazCF2(List<Component> pool, Set<Component> usados,
			Set<Component> perdidos) {
		for (Component c : pool) {
			if (usados.contains(c)) {
				continue;
			}
			List<ProvidedInterface> validas = new ArrayList<ProvidedInterface>();
			for (ProvidedInterface pi : c.getProvidedInterfaces()) {
				if (this.usadaPorComponenteIntacto(pi, perdidos)) {
					validas.add(pi);
				}
			}
			if (!validas.isEmpty()) {
				return validas.get(this.rnd.nextInt(validas.size()));
			}
		}
		return null;
	}

	private boolean usadaPorComponenteIntacto(ProvidedInterface pi, Set<Component> perdidos) {
		for (Connector conn : pi.getConnectors()) {
			RequiredInterface ri = conn.getSource();
			if (ri != null) {
				Component usuario = ri.getComponent();
				if (usuario != null && usuario.getState() == ComponentState.STARTED && !perdidos.contains(usuario)) {
					return true;
				}
			}
		}
		return false;
	}

	private List<Connector> elegirConectores(List<Component> pool, Set<Component> perdidos, int cantidad) {
		List<Connector> candidatos = new ArrayList<Connector>();
		for (Component c : pool) {
			if (perdidos.contains(c)) {
				continue;
			}
			for (RequiredInterface ri : c.getRequiredInterfaces()) {
				Connector k = ri.getConnector();
				if (k != null && k.getTarget() != null) {
					Component destino = k.getTarget().getComponent();
					if (destino != null && destino.getState() == ComponentState.STARTED
							&& !perdidos.contains(destino)) {
						candidatos.add(k);
					}
				}
			}
		}
		Collections.shuffle(candidatos, this.rnd);
		if (candidatos.size() > cantidad) {
			return new ArrayList<Connector>(candidatos.subList(0, cantidad));
		}
		return candidatos;
	}

	private boolean esFiltro(Component component) {
		for (ProvidedInterface pi : component.getProvidedInterfaces()) {
			if (pi.getType().getFqName().equals(MRubisNames.itemFilterService_InterfaceType_FqName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Envuelve un inyector para contar intentos y éxitos reales.
	 */
	private class RegistroInyector<T extends ArchitecturalElement> implements Injector<T> {

		private final Injector<T> delegado;

		RegistroInyector(Injector<T> delegado) {
			this.delegado = delegado;
		}

		@Override
		public boolean inject(T elemento) {
			boolean exito = this.delegado.inject(elemento);
			BurstScenario.this.inyeccionesIntentadas++;
			if (exito) {
				BurstScenario.this.inyeccionesExitosas++;
			}
			return exito;
		}
	}

}
