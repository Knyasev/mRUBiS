package de.mdelab.simulator.mrubis.examples.selfhealing.statemape;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.util.EcoreUtil;

import de.mdelab.comparch.AdaptationStrategy;
import de.mdelab.comparch.Annotations;
import de.mdelab.comparch.Architecture;

/**
 * Presupuesto de reparación COMÚN a las tres estrategias (versión 3).
 *
 * Tras la fase Plan, recorre las estrategias de adaptación anotadas en el
 * modelo EN EL ORDEN EN QUE LAS PLANIFICÓ cada planificador y conserva solo
 * las que caben en la capacidad de la ronda (se omiten las que no caben y se
 * sigue con las siguientes). Las descartadas se eliminan del modelo, de modo
 * que Execute no las ejecuta; los problemas correspondientes se vuelven a
 * detectar en la ronda siguiente.
 *
 * El orden es lo que diferencia a las estrategias:
 *  - STATIC: orden de detección de Analyze (CF1, CF2, CF3, CF5).
 *  - UTILITY_DRIVEN: utilidad estimada descendente.
 *  - SOLVER: ya selecciona el subconjunto óptimo dentro de la misma
 *    capacidad, por lo que este recorte no le cambia nada.
 *
 * Los costos estimados (en la unidad de la capacidad) COINCIDEN con
 * SolverPlan.getCost(); si se modifica uno, hay que modificar el otro.
 *
 * Contribución técnica original –
 * Wilson Israel Gonzalez Armijos
 */
public final class PresupuestoReparacion {

	private static final double EPS = 1e-9;

	private PresupuestoReparacion() {
	}

	/**
	 * Costo estimado de ejecutar una estrategia de adaptación.
	 *
	 * @param tipoEstrategia
	 *            tipo de la {@link AdaptationStrategy}
	 */
	public static double costo(String tipoEstrategia) {
		switch (tipoEstrategia) {
		case "RestartComponent": // CF2
			return 5.0;
		case "LwRedeployComponent": // CF1
			return 8.0;
		case "RecreateConnector": // CF5
			return 10.0;
		case "HwRedeployComponent": // CF3
			return 15.0;
		default:
			return 20.0;
		}
	}

	/**
	 * Recorta las estrategias planificadas a la capacidad dada.
	 *
	 * @param architecture
	 *            raíz del modelo
	 * @param capacidad
	 *            capacidad de reparación de la ronda
	 * @return número de estrategias descartadas
	 */
	public static int aplicar(Architecture architecture, double capacidad) {
		Annotations annotations = architecture.getAnnotations();
		List<AdaptationStrategy> planificadas = new ArrayList<AdaptationStrategy>(annotations.getAdaptationStrategies());

		double restante = capacidad;
		int descartadas = 0;

		for (AdaptationStrategy estrategia : planificadas) {
			double costo = costo(estrategia.getType());
			if (costo <= restante + EPS) {
				restante -= costo;
			} else {
				EcoreUtil.delete(estrategia, true);
				descartadas++;
			}
		}
		return descartadas;
	}
}
