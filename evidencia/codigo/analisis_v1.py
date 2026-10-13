"""
===============================================================================
 Objetivo Específico 2 (OE2) - Fase 2 - Actividad 1
 Estadística descriptiva de las métricas experimentales
===============================================================================

QUÉ HACE ESTE SCRIPT
--------------------
Lee el CSV consolidado del diseño factorial (estrategia x nivel CF x
repetición) generado por StateBasedMapeFeedbackLoop.main() y calcula
estadísticos descriptivos de tres métricas:

    - T_ajuste  (tiempo de ajuste, en ms)
    - T_estab   (tiempo de estabilización, en ms)
    - Utilidad  (utilidad acumulada tras la autocuración)

Cada métrica se resume por cada combinación de estrategia de planificación
(STATIC, UTILITY_DRIVEN, SOLVER) y nivel de severidad (CF1, CF2, CF3, CF5).

ENTRADA
-------
CSV separado por ";" con las columnas:
    estrategia, cf, repeticion, t_ajuste_ms, t_estab_ms, utilidad

SALIDA (carpeta ./resultados_objetivo2/)
----------------------------------------
    descriptivos_t_ajuste.csv / .tex
    descriptivos_t_estab.csv  / .tex
    descriptivos_utilidad.csv / .tex
    tasa_no_estabilizacion.csv / .tex

PROCEDIMIENTO
-------------
1. Carga el CSV.
2. Marca como "censuradas" las repeticiones con t_estab_ms = -1. Ese valor
   es un centinela que escribe el ciclo MAPE-K cuando la utilidad no se
   mantuvo sobre el umbral de estabilización durante las rondas
   consecutivas requeridas dentro de la ventana de simulación; no es un
   tiempo medido.
3. Calcula los descriptivos de T_ajuste y de utilidad con todas las filas.
4. Calcula los descriptivos de T_estab solo con las filas no censuradas,
   para que el centinela -1 no altere las medidas de tendencia central.
5. Calcula la tasa de no-estabilización (% de repeticiones censuradas) por
   estrategia y nivel CF, y la presenta como matriz (estrategias en filas,
   niveles CF en columnas).

Estadísticos calculados: n, media, mediana, desviación estándar, mínimo,
primer cuartil (Q1), tercer cuartil (Q3) y máximo.

REQUISITOS
----------
    pip install pandas jinja2
    (jinja2 lo requiere DataFrame.to_latex())

EJECUCIÓN
---------
    python oe2_act1_descriptivos.py
===============================================================================
"""

import os
import pandas as pd

# ------------------------------------------------------------------
# CONFIGURACIÓN
# ------------------------------------------------------------------

# Ruta absoluta al CSV consolidado. Ajustar si cambia su ubicación.
CSV_PATH = r"C:\Users\HP-User\Desktop\Wilson Gonzalez\8 Ciclo\mRUBiS\evidencia\datasets\experimento_factorial_completo_v1_0.90.csv"

# Carpeta de salida de las tablas.
OUT_DIR = r"C:\Users\HP-User\Desktop\Wilson Gonzalez\8 Ciclo\mRUBiS\evidencia\salidas\analisis_v1"
os.makedirs(OUT_DIR, exist_ok=True)

# Nombres de columnas del CSV.
COL_ESTRATEGIA = "estrategia"
COL_CF = "cf"
COL_TAJUSTE = "t_ajuste_ms"
COL_TESTAB = "t_estab_ms"
COL_UTILIDAD = "utilidad"

# Orden de presentación en las tablas de matriz.
ORDEN_ESTRATEGIAS = ["STATIC", "UTILITY_DRIVEN", "SOLVER"]
ORDEN_CF = ["CF1", "CF2", "CF3", "CF5"]

# El MetricsCollector escribe el CSV con ";" como separador.
df = pd.read_csv(CSV_PATH, sep=";")

# Columna auxiliar: True cuando la repetición no estabilizó (t_estab_ms = -1).
df["estab_censurada"] = df[COL_TESTAB] == -1


def tabla_descriptiva(metric_col, df_in, nombre_archivo):
    """
    Calcula y guarda los estadísticos descriptivos de una métrica,
    agrupados por (estrategia, nivel CF).

    Parameters
    ----------
    metric_col : str
        Columna numérica a resumir (p. ej. "t_ajuste_ms").
    df_in : pandas.DataFrame
        Datos a resumir (el DataFrame completo, o el subconjunto sin
        repeticiones censuradas).
    nombre_archivo : str
        Nombre base, sin extensión, de los archivos .csv y .tex de salida.

    Returns
    -------
    pandas.DataFrame
        Tabla con n, media, mediana, de, minimo, q1, q3 y maximo.
    """
    g = df_in.groupby([COL_ESTRATEGIA, COL_CF])[metric_col]
    tabla = g.agg(
        n="count",
        media="mean",
        mediana="median",
        de="std",
        minimo="min",
        q1=lambda x: x.quantile(0.25),
        q3=lambda x: x.quantile(0.75),
        maximo="max",
    ).round(3)

    # .csv para las actividades siguientes; .tex para incluir en LaTeX.
    tabla.to_csv(os.path.join(OUT_DIR, f"{nombre_archivo}.csv"))
    tabla.to_latex(os.path.join(OUT_DIR, f"{nombre_archivo}.tex"))

    print(f"\n=== Descriptivos: {metric_col} ===")
    print(tabla)
    return tabla


print("########## OE2 - ACTIVIDAD 1: ESTADÍSTICA DESCRIPTIVA ##########")

# T_ajuste: todas las filas.
tabla_descriptiva(COL_TAJUSTE, df, "descriptivos_t_ajuste")

# T_estab: solo repeticiones que estabilizaron (excluye t_estab_ms = -1).
df_estab_ok = df[~df["estab_censurada"]]
tabla_descriptiva(COL_TESTAB, df_estab_ok, "descriptivos_t_estab")

# Utilidad acumulada: todas las filas.
tabla_descriptiva(COL_UTILIDAD, df, "descriptivos_utilidad")

# Tasa de no-estabilización (%): proporción de repeticiones censuradas por
# celda, en formato matriz (filas = estrategia, columnas = nivel CF).
tasa_censura = (
    df.groupby([COL_ESTRATEGIA, COL_CF])["estab_censurada"]
    .mean()
    .mul(100)
    .round(2)
    .unstack(COL_CF)
    .reindex(index=ORDEN_ESTRATEGIAS, columns=ORDEN_CF)
)
tasa_censura.to_csv(os.path.join(OUT_DIR, "tasa_no_estabilizacion.csv"))
tasa_censura.to_latex(os.path.join(OUT_DIR, "tasa_no_estabilizacion.tex"))

print("\n=== Tasa de no estabilización (%) por estrategia y nivel CF ===")
print(tasa_censura)

print(f"\nArchivos generados en: {OUT_DIR}")