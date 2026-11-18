"""OE2 - Actividad 1: validación y estadística descriptiva del experimento v3.

Uso:
  pip install pandas numpy
  python oe2_act1_descriptivos_v3.py --csv "experimento_v3.csv" --salida "resultados_objetivo2"

El CSV usa ';'. Los archivos de salida se generan en UTF-8.
Las combinaciones escenario/tamaño se detectan del CSV; no se inventan celdas.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import sys
import numpy as np
import pandas as pd

ESTRATEGIAS = ["STATIC", "UTILITY_DRIVEN", "SOLVER"]
ESCENARIOS = ["CF1", "CF2", "CF3", "CF5", "MIXTO"]
CLAVES = ["estrategia", "escenario", "tamano"]
ID = CLAVES + ["repeticion"]
METRICAS = ["t_ajuste_ms", "t_estab_ms", "t_plan_ms", "utilidad_acum", "perdida_acum"]
EXTRAS = ["rondas_ajuste", "rondas_estab", "utilidad_final", "problemas_final", "problemas_acum", "reparaciones_ejecutadas", "reparaciones_diferidas", "t_plan_por_plan_ms"]
REQUERIDAS = ID + ["semilla", "perdida_inicial", "rondas_total", "n_planificaciones"] + METRICAS
NUMERICAS = list(dict.fromkeys(["tamano", "repeticion", "semilla", "perdida_inicial", "rondas_total", "n_planificaciones"] + METRICAS + EXTRAS + ["utilidad_sana", "capacidad", "valida"]))


def estadisticos(df: pd.DataFrame, metrica: str, indice: pd.MultiIndex) -> pd.DataFrame:
    g = df.groupby(CLAVES, observed=True)[metrica]
    out = g.agg(n="count", media="mean", mediana="median", de="std", minimo="minimo" if False else "min", maximo="max")
    out["q1"] = g.quantile(.25)
    out["q3"] = g.quantile(.75)
    out["iqr"] = out["q3"] - out["q1"]
    return out.reindex(indice)[["n", "media", "mediana", "de", "minimo", "q1", "q3", "iqr", "maximo"]].round(3)


def exportar(tabla: pd.DataFrame, ruta: Path) -> None:
    tabla.to_csv(ruta.with_suffix(".csv"), sep=";", encoding="utf-8-sig", decimal=",")


def ejecutar(csv: Path, salida: Path, esperadas: int, repeticiones: int) -> int:
    if not csv.is_file():
        print(f"ERROR: no existe el archivo: {csv}", file=sys.stderr)
        return 2
    salida.mkdir(parents=True, exist_ok=True)
    df = pd.read_csv(csv, sep=";", encoding="utf-8-sig", low_memory=False)
    df.columns = df.columns.str.strip()
    faltantes = sorted(set(REQUERIDAS) - set(df.columns))
    if faltantes:
        print("ERROR: faltan columnas obligatorias: " + ", ".join(faltantes), file=sys.stderr)
        return 2
    df["estrategia"] = df["estrategia"].astype("string").str.strip().str.upper()
    df["escenario"] = df["escenario"].astype("string").str.strip().str.upper()
    errores_conversion = {}
    for c in NUMERICAS:
        if c in df.columns and c != "valida":
            antes = df[c].notna()
            df[c] = pd.to_numeric(df[c].astype("string").str.replace(",", ".", regex=False), errors="coerce")
            errores_conversion[c] = int((antes & df[c].isna()).sum())

    informe = [f"Archivo: {csv.resolve()}", f"Filas: {len(df)}", f"Filas esperadas (referencia): {esperadas}",
               f"Coincide con total esperado: {len(df) == esperadas}", f"Duplicados por {ID}: {int(df.duplicated(ID).sum())}",
               f"Filas completamente duplicadas: {int(df.duplicated().sum())}", "", "Conversiones numéricas fallidas:"]
    informe += [f"  {c}: {n}" for c, n in errores_conversion.items() if n] or ["  Ninguna"]
    informe += ["", "Valores faltantes por columna:"]
    informe += [f"  {c}: {int(n)}" for c, n in df.isna().sum().items() if n] or ["  Ninguno"]
    informe += ["", "Estrategias desconocidas: " + str(sorted(set(df["estrategia"].dropna()) - set(ESTRATEGIAS))),
                "Escenarios desconocidos: " + str(sorted(set(df["escenario"].dropna()) - set(ESCENARIOS)))]

    condiciones = df[["escenario", "tamano"]].dropna().drop_duplicates()
    condiciones = sorted([(str(r.escenario), int(r.tamano)) for r in condiciones.itertuples(index=False)],
                        key=lambda x: (ESCENARIOS.index(x[0]) if x[0] in ESCENARIOS else 999, x[1]))
    indice = pd.MultiIndex.from_tuples([(e, cf, k) for e in ESTRATEGIAS for cf, k in condiciones], names=CLAVES)
    conteos = df.groupby(CLAVES).size().reindex(indice, fill_value=0)
    informe += ["", f"Condiciones escenario/tamaño detectadas: {len(condiciones)}", str(condiciones),
                f"Celdas estrategia/condición esperadas: {len(indice)}", f"Celdas con n distinto de {repeticiones}: {int((conteos != repeticiones).sum())}",
                "", "Conteos por celda:", conteos.to_string()]
    conteos.rename("n").to_csv(salida / "conteos_por_celda.csv", sep=";", encoding="utf-8-sig")

    # Las semillas deben coincidir entre estrategias para la misma condición y repetición.
    sem = df.groupby(["escenario", "tamano", "repeticion"])["semilla"].nunique(dropna=False)
    informe += ["", f"Bloques con semillas distintas entre estrategias: {int((sem > 1).sum())}"]
    bloques = df.groupby(["escenario", "tamano", "repeticion"])["estrategia"].nunique()
    informe += [f"Bloques sin las tres estrategias: {int((bloques != len(ESTRATEGIAS)).sum())}"]
    informe += [f"Valores de rondas_total: {sorted(df['rondas_total'].dropna().unique().tolist())}"]
    if "capacidad" in df:
        informe += [f"Valores de capacidad: {sorted(df['capacidad'].dropna().unique().tolist())}"]
    if "valida" in df:
        informe += ["Valores de valida: " + str(df["valida"].value_counts(dropna=False).to_dict())]
    informe += [f"Pérdida inicial <= 0: {int((df['perdida_inicial'] <= 0).sum())}",
                f"n_planificaciones <= 0: {int((df['n_planificaciones'] <= 0).sum())}"]

    # -1 es marcador de evento no alcanzado, NO una duración real.
    for c in ["t_ajuste_ms", "t_estab_ms"]:
        df[f"{c}_no_alcanzado"] = df[c].eq(-1)
        informe += [f"{c} = -1: {int(df[f'{c}_no_alcanzado'].sum())}",
                    f"{c} < -1: {int((df[c] < -1).sum())}"]
        df[c] = df[c].where(df[c] >= 0, np.nan)
    df["perdida_no_positiva"] = df["perdida_inicial"].le(0)
    df["t_plan_por_plan_ms"] = df["t_plan_ms"].div(df["n_planificaciones"].where(df["n_planificaciones"] > 0))
    df["t_plan_por_plan_ms"] = df["t_plan_por_plan_ms"].replace([np.inf, -np.inf], np.nan)

    for c in METRICAS + [x for x in EXTRAS if x in df]:
        exportar(estadisticos(df, c, indice), salida / f"descriptivos_{c}")

    resumen = []
    for metrica in METRICAS:
        t = estadisticos(df, metrica, indice).reset_index()
        t.insert(3, "metrica", metrica)
        resumen.append(t)
    pd.concat(resumen, ignore_index=True).to_csv(salida / "descriptivos_consolidados.csv", sep=";", index=False, encoding="utf-8-sig", decimal=",")

    tasas = df.groupby(CLAVES).agg(
        n_total=("t_estab_ms_no_alcanzado", "size"),
        n_no_estabilizadas=("t_estab_ms_no_alcanzado", "sum"),
        n_sin_ajuste=("t_ajuste_ms_no_alcanzado", "sum"),
        n_perdida_no_positiva=("perdida_no_positiva", "sum"),
    ).reindex(indice, fill_value=0)
    tasas["tasa_no_estabilizacion_pct"] = (100 * tasas["n_no_estabilizadas"] / tasas["n_total"].replace(0, np.nan)).round(2)
    tasas["tasa_sin_ajuste_pct"] = (100 * tasas["n_sin_ajuste"] / tasas["n_total"].replace(0, np.nan)).round(2)
    exportar(tasas, salida / "tasa_no_estabilizacion")
    df.loc[df["t_estab_ms_no_alcanzado"], [c for c in ID + ["semilla", "perdida_inicial", "rondas_total"] if c in df]].to_csv(
        salida / "repeticiones_no_estabilizadas.csv", sep=";", index=False, encoding="utf-8-sig")
    if "problemas_final" in df:
        df.groupby(CLAVES)["problemas_final"].agg(["count", "mean", "median", "max"]).reindex(indice).to_csv(
            salida / "resumen_problemas_pendientes.csv", sep=";", encoding="utf-8-sig")

    informe += ["", "NOTAS:", "- Las estadísticas de tiempos excluyen valores negativos (-1) y NaN; las tasas conservan el denominador completo.",
                "- La ausencia de estabilización se cuenta por marcador -1; comprobar en trazas su causa.",
                "- Las celdas se detectan del CSV, por lo que el control de diseño debe contrastarse con el protocolo OE1."]
    (salida / "controles_integridad.txt").write_text("\n".join(informe) + "\n", encoding="utf-8")
    print("\n".join(informe[:8]))
    print(f"\nAnálisis descriptivo generado en: {salida.resolve()}")
    return 0


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--csv", type=Path, default=Path("C:\\Users\\HP-User\\Desktop\\Wilson Gonzalez\\8 Ciclo\\mRUBiS\\evidencia\\datasets\\experimento_v3.csv"))
    p.add_argument("--salida", type=Path, default=Path("C:\\Users\\HP-User\\Desktop\\Wilson Gonzalez\\8 Ciclo\\mRUBiS\\evidencia\\salidas\\analisis_v3"))
    p.add_argument("--esperadas", type=int, default=1350)
    p.add_argument("--repeticiones", type=int, default=30)
    a = p.parse_args()
    raise SystemExit(ejecutar(a.csv, a.salida, a.esperadas, a.repeticiones))
