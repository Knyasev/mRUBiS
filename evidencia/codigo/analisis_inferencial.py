"""OE2, Actividad 2: análisis inferencial emparejado de mRUBiS.

Instalación: pip install pandas numpy scipy
Ejecución: python oe2_act2_inferencial_actualizado.py --csv "experimento_v3(1).csv"

Contrastes: Shapiro-Wilk (diagnóstico), Friedman (global), Wilcoxon pareado
con Bonferroni (post hoc), correlación biserial de rangos pareados.
Familias: 15 contrastes globales por métrica y 3 comparaciones post hoc
por escenario/tamaño y métrica con global significativo.
"""
from __future__ import annotations

import argparse
import itertools
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

ALPHA = 0.05
ESTRATEGIAS = ["STATIC", "UTILITY_DRIVEN", "SOLVER"]
METRICAS = {
    "t_ajuste_ms": "todas",
    "t_estab_ms": "sin_censura",
    "utilidad_acum": "todas",
    "perdida_acum": "todas",
    "t_plan_ms": "todas",
}
PARES = list(itertools.combinations(ESTRATEGIAS, 2))
BLOQUE = ["escenario", "tamano", "repeticion", "semilla"]


def bonferroni(p, n):
    return min(float(p) * n, 1.0) if np.isfinite(p) else np.nan


def rank_biserial_pareada(a, b):
    """(W+ - W-)/(W+ + W-) de diferencias a-b, excluyendo ceros.

    Los empates en |d| reciben rangos promedio, igual que Wilcoxon.
    Signo positivo: la primera estrategia tiende a valores mayores.
    """
    d = np.asarray(a, dtype=float) - np.asarray(b, dtype=float)
    d = d[np.isfinite(d) & (d != 0)]
    if len(d) == 0:
        return 0.0
    rangos = stats.rankdata(np.abs(d), method="average")
    w_pos = float(rangos[d > 0].sum())
    w_neg = float(rangos[d < 0].sum())
    return (w_pos - w_neg) / (w_pos + w_neg)


def cargar_datos(ruta):
    df = pd.read_csv(ruta, sep=None, engine="python")
    requeridas = set(BLOQUE + ["estrategia", *METRICAS])
    faltantes = requeridas - set(df.columns)
    if faltantes:
        raise ValueError(f"Columnas obligatorias ausentes: {sorted(faltantes)}")
    for c in ["tamano", "repeticion", "semilla", *METRICAS]:
        df[c] = pd.to_numeric(df[c], errors="raise")
    if df[BLOQUE + ["estrategia"]].isna().any().any():
        raise ValueError("Hay valores faltantes en las claves de emparejamiento")
    if df.duplicated(BLOQUE + ["estrategia"]).any():
        raise ValueError("Existen bloques/estrategias duplicados")
    # Evita que una misma repetición se empareje por error con semillas diferentes.
    n_semillas = df.groupby(["escenario", "tamano", "repeticion"])["semilla"].nunique()
    if (n_semillas > 1).any():
        raise ValueError("Una repetición tiene semillas distintas entre estrategias")
    desconocidas = set(df["estrategia"].unique()) - set(ESTRATEGIAS)
    if desconocidas:
        raise ValueError(f"Estrategias no reconocidas: {desconocidas}")
    if (df["t_estab_ms"] < -1).any():
        raise ValueError("Hay tiempos de estabilización inferiores a -1")
    for metrica in METRICAS:
        if metrica != "t_estab_ms" and (df[metrica] < 0).any():
            raise ValueError(f"La métrica {metrica} contiene valores negativos")
    df["estab_censurada"] = df["t_estab_ms"].eq(-1)
    return df


def preparar_pareados(sub, metrica):
    """Una fila por (repetición, semilla), una columna por estrategia."""
    tabla = sub.pivot(index=["repeticion", "semilla"],
                      columns="estrategia", values=metrica)
    tabla = tabla.reindex(columns=ESTRATEGIAS)
    tabla = tabla.replace([np.inf, -np.inf], np.nan)
    if metrica == "t_estab_ms":
        tabla = tabla.mask(tabla.eq(-1))
    completos = tabla.dropna(subset=ESTRATEGIAS)
    return tabla, completos


def analizar(df):
    combinaciones = sorted(df[["escenario", "tamano"]].drop_duplicates().itertuples(index=False, name=None),
                           key=lambda x: (str(x[0]), x[1]))
    n_globales = len(combinaciones)
    normalidad, friedman, wilcoxon, control, censura = [], [], [], [], []

    for escenario, tamano in combinaciones:
        sub = df[(df["escenario"] == escenario) & (df["tamano"] == tamano)]
        conteos = sub.groupby("estrategia").size().to_dict()
        tabla_semillas = sub.pivot(index=["repeticion", "semilla"],
                                   columns="estrategia", values="tamano")
        n_completos_diseno = int(tabla_semillas.reindex(columns=ESTRATEGIAS).notna().all(axis=1).sum())
        control.append({"escenario": escenario, "tamano": tamano,
                        **{f"n_{e}": conteos.get(e, 0) for e in ESTRATEGIAS},
                        "bloques_completos_diseno": n_completos_diseno})
        for e in ESTRATEGIAS:
            s = sub[sub["estrategia"] == e]
            censura.append({"escenario": escenario, "tamano": tamano,
                            "estrategia": e, "n": len(s),
                            "no_estabilizaciones": int(s["estab_censurada"].sum())})

        for metrica in METRICAS:
            base = {"metrica": metrica, "escenario": escenario, "tamano": tamano}
            tabla, completos = preparar_pareados(sub, metrica)
            n_excluidos = len(tabla) - len(completos)
            for e in ESTRATEGIAS:
                g = tabla[e].dropna().to_numpy(dtype=float)
                valido = 3 <= len(g) <= 5000 and len(np.unique(g)) > 1
                w, p = stats.shapiro(g) if valido else (np.nan, np.nan)
                normalidad.append({**base, "estrategia": e, "n": len(g),
                                   "W": w, "p": p,
                                   "normal": bool(p >= ALPHA) if valido else None,
                                   "estado": "calculada" if valido else "insuficiente o constante"})

            arrays = [completos[e].to_numpy(dtype=float) for e in ESTRATEGIAS]
            n = len(completos)
            if n < 2:
                chi2, p_global, estado = np.nan, np.nan, "bloques insuficientes"
            elif np.all(np.column_stack(arrays) == arrays[0][:, None]):
                chi2, p_global, estado = 0.0, 1.0, "tres estrategias idénticas en todos los bloques"
            else:
                chi2, p_global = stats.friedmanchisquare(*arrays)
                estado = "calculada" if np.isfinite(p_global) else "no calculable"
            p_global_adj = bonferroni(p_global, n_globales)
            significativo = bool(np.isfinite(p_global_adj) and p_global_adj < ALPHA)
            friedman.append({**base, "n_bloques": n, "bloques_excluidos": n_excluidos,
                             "chi2_friedman": chi2, "p_friedman": p_global,
                             "p_friedman_bonferroni": p_global_adj,
                             "n_contrastes_familia": n_globales,
                             "significativo_bonferroni": significativo,
                             "estado": estado})

            if significativo:
                for e1, e2 in PARES:
                    a = completos[e1].to_numpy(dtype=float)
                    b = completos[e2].to_numpy(dtype=float)
                    diferencias = a - b
                    n_no_cero = int(np.count_nonzero(diferencias))
                    if n_no_cero == 0:
                        w, p_par = 0.0, 1.0
                    else:
                        # scipy usa Wilcoxon con rangos promediados en empates.
                        # Pasar diferencias redondeadas evita diferencias numéricas espurias.
                        dif = np.round(diferencias, decimals=10)
                        w, p_par = stats.wilcoxon(dif, zero_method="wilcox",
                                                   alternative="two-sided", method="auto")
                    p_par_adj = bonferroni(p_par, len(PARES))
                    efecto = rank_biserial_pareada(np.round(a-b, decimals=10), np.zeros(len(a)))
                    wilcoxon.append({**base, "par": f"{e1} vs {e2}",
                                     "n_pares": n, "n_diferencias_no_cero": n_no_cero,
                                     "W_wilcoxon": w, "p_wilcoxon": p_par,
                                     "p_wilcoxon_bonferroni": p_par_adj,
                                     "rank_biserial_pareada": efecto,
                                     "significativo": bool(p_par_adj < ALPHA)})

    return {
        "control_diseno": pd.DataFrame(control),
        "censura_descriptiva": pd.DataFrame(censura),
        "normalidad_shapiro": pd.DataFrame(normalidad),
        "friedman": pd.DataFrame(friedman),
        "posthoc_wilcoxon_bonferroni": pd.DataFrame(wilcoxon, columns=[
            "metrica", "escenario", "tamano", "par", "n_pares",
            "n_diferencias_no_cero", "W_wilcoxon", "p_wilcoxon",
            "p_wilcoxon_bonferroni", "rank_biserial_pareada", "significativo"]),
    }


def main():
    parser = argparse.ArgumentParser(description="OE2 Actividad 2: análisis inferencial emparejado")
    parser.add_argument("--csv", type=Path, default=Path("C:\\Users\\HP-User\\Desktop\\Wilson Gonzalez\\8 Ciclo\\mRUBiS\\evidencia\\datasets\\experimento_v3.csv"))
    parser.add_argument("--salida", type=Path, default=Path("C:\\Users\\HP-User\\Desktop\\Wilson Gonzalez\\8 Ciclo\\mRUBiS\\evidencia\\salidas\\analisis_inferencial"))
    args = parser.parse_args()
    df = cargar_datos(args.csv)
    args.salida.mkdir(parents=True, exist_ok=True)
    tablas = analizar(df)
    for nombre, tabla in tablas.items():
        tabla.to_csv(args.salida / f"{nombre}.csv", sep=";", index=False)
        if nombre not in ("control_diseno", "censura_descriptiva"):
            (args.salida / f"{nombre}.tex").write_text(
                tabla.to_latex(index=False, float_format="%.5g"), encoding="utf-8")
    print(f"Ejecuciones: {len(df)} | Escenarios/tamaños: {len(tablas['control_diseno'])}")
    print(f"Censuradas: {df.estab_censurada.sum()} | Métricas: {len(METRICAS)}")
    print(f"Friedman significativos ajustados: {tablas['friedman'].significativo_bonferroni.sum()}")
    print(f"Comparaciones Wilcoxon calculadas: {len(tablas['posthoc_wilcoxon_bonferroni'])}")
    print(f"Resultados guardados en: {args.salida.resolve()}")

if __name__ == "__main__":
    main()
