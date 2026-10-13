"""
===============================================================================
 Objetivo Específico 2 (OE2) - Fase 2 - Actividad 1
 Estadística descriptiva de las métricas experimentales (v3 con ráfagas)
===============================================================================

QUÉ HACE ESTE SCRIPT
--------------------
Lee el CSV consolidado del diseño factorial (estrategia × nivel CF × carga ×
repetición) generado por StateBasedMapeFeedbackLoop.main() y calcula
estadísticos descriptivos de las métricas registradas, por cada
combinación de:
    - estrategia de planificación (STATIC, UTILITY_DRIVEN, SOLVER)
    - nivel de severidad (CF1, CF2, CF3, CF5)
    - carga de fallas por ronda (1, 10, 50, 100)

ENTRADA
-------
CSV separado por ";" con las columnas:
    estrategia, cf, fallas_por_ronda, repeticion, t_ajuste_ms, t_estab_ms,
    rondas_ajuste, rondas_estab, rondas_total, utilidad_sana,
    utilidad_final, utilidad_acum, utilidad_acum_postfalla, t_plan_ms,
    n_planificaciones, problemas_pendientes

MÉTRICAS Y CONJUNTO DE DATOS USADO EN CADA UNA
----------------------------------------------
    t_ajuste_ms              todas las repeticiones
    rondas_ajuste            todas las repeticiones
    t_estab_ms               solo repeticiones que estabilizaron
    rondas_estab             solo repeticiones que estabilizaron
    utilidad_final           todas las repeticiones
    utilidad_acum            todas las repeticiones
    utilidad_acum_postfalla  todas las repeticiones
    t_plan_ms                todas las repeticiones (total por repetición)
    t_plan_por_plan_ms       todas las repeticiones (t_plan_ms / n_planificaciones)
    problemas_pendientes     todas las repeticiones

SALIDA (carpeta ./resultados_objetivo2/)
----------------------------------------
    descriptivos_<metrica>.csv / .tex     una tabla por métrica
    tasa_no_estabilizacion.csv / .tex     matriz estrategia × CF × carga, en %
    repeticiones_no_estabilizadas.csv     conteo de repeticiones censuradas
    controles_integridad.txt              verificaciones del dataset

PROCEDIMIENTO
-------------
1. Carga el CSV y ejecuta controles de integridad del dataset (número de
   filas por celda, rondas ejecutadas por repetición, utilidad del sistema
   sano, coherencia entre utilidad final y utilidad sana, valores de
   problemas pendientes).
2. Marca como "censuradas" las repeticiones con t_estab_ms = -1. Ese valor
   es un centinela que escribe el ciclo MAPE-K cuando la utilidad no se
   mantuvo sobre el umbral de estabilización durante las rondas
   consecutivas requeridas dentro de la ventana de simulación; no es un
   tiempo medido. Esas repeticiones se excluyen de los descriptivos de
   t_estab_ms y rondas_estab, y se reportan aparte como tasa de no
   estabilización.
3. Deriva t_plan_por_plan_ms = t_plan_ms / n_planificaciones.
4. Calcula, por celda, n, media, mediana, desviación estándar, mínimo, Q1,
   Q3, rango intercuartílico (IQR = Q3 - Q1) y máximo.

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

# Ruta absoluta al CSV consolidado (v3 con ráfagas).
CSV_PATH = r"C:\Users\HP-User\Desktop\Wilson Gonzalez\8 Ciclo\mRUBiS\evidencia\datasets\experimento_factorial_v3_rafagas.csv"

# Carpeta de salida de las tablas.
OUT_DIR = r"C:\Users\HP-User\Desktop\Wilson Gonzalez\8 Ciclo\mRUBiS\evidencia\salidas\analisis_v3"
os.makedirs(OUT_DIR, exist_ok=True)

# Nombres de columnas del CSV.
COL_ESTRATEGIA = "estrategia"
COL_CF = "cf"
COL_CARGA = "fallas_por_ronda"
COL_TESTAB = "t_estab_ms"

# Orden de presentación.
ORDEN_ESTRATEGIAS = ["STATIC", "UTILITY_DRIVEN", "SOLVER"]
ORDEN_CF = ["CF1", "CF2", "CF3", "CF5"]
ORDEN_CARGA = [1, 10, 50, 100]

# Métricas que usan todas las repeticiones.
METRICAS_TODAS = [
    "t_ajuste_ms",
    "rondas_ajuste",
    "utilidad_final",
    "utilidad_acum",
    "utilidad_acum_postfalla",
    "t_plan_ms",
    "t_plan_por_plan_ms",
    "problemas_pendientes",
]

# Métricas que solo se calculan con repeticiones que estabilizaron.
METRICAS_SIN_CENSURA = [
    "t_estab_ms",
    "rondas_estab",
]

# El MetricsCollector escribe el CSV con ";" como separador.
df = pd.read_csv(CSV_PATH, sep=";")

# Columna auxiliar: True cuando la repetición no estabilizó (t_estab_ms = -1).
df["estab_censurada"] = df[COL_TESTAB] == -1

# Costo medio de una ejecución de la fase Plan.
df["t_plan_por_plan_ms"] = df["t_plan_ms"] / df["n_planificaciones"]


# ------------------------------------------------------------------
# FUNCIONES AUXILIARES
# ------------------------------------------------------------------
def tabla_descriptiva(metric_col, df_in):
    """
    Calcula y guarda los estadísticos descriptivos de una métrica,
    agrupados por (estrategia, nivel CF, carga).

    Parameters
    ----------
    metric_col : str
        Columna numérica a resumir.
    df_in : pandas.DataFrame
        Datos a resumir (el DataFrame completo, o el subconjunto sin
        repeticiones censuradas).

    Returns
    -------
    pandas.DataFrame
        Tabla con n, media, mediana, de, minimo, q1, q3, iqr y maximo.
    """
    g = df_in.groupby([COL_ESTRATEGIA, COL_CF, COL_CARGA])[metric_col]
    tabla = g.agg(
        n="count",
        media="mean",
        mediana="median",
        de="std",
        minimo="min",
        q1=lambda x: x.quantile(0.25),
        q3=lambda x: x.quantile(0.75),
        iqr=lambda x: x.quantile(0.75) - x.quantile(0.25),
        maximo="max",
    ).round(3)

    # Orden de filas: estrategia, CF y carga según los órdenes definidos.
    indice = pd.MultiIndex.from_product(
        [ORDEN_ESTRATEGIAS, ORDEN_CF, ORDEN_CARGA],
        names=[COL_ESTRATEGIA, COL_CF, COL_CARGA]
    )
    tabla = tabla.reindex(indice)

    # .csv para las actividades siguientes; .tex para incluir en LaTeX.
    tabla.to_csv(os.path.join(OUT_DIR, f"descriptivos_{metric_col}.csv"))
    tabla.to_latex(os.path.join(OUT_DIR, f"descriptivos_{metric_col}.tex"))

    print(f"\n=== Descriptivos: {metric_col} ===")
    print(tabla.head(20))  # primeros 20 para no saturar consola
    if len(tabla) > 20:
        print(f"... ({len(tabla)} filas en total, ver CSV completo)")
    return tabla


def controles_integridad(datos):
    """
    Verifica propiedades básicas del dataset y las imprime y guarda en
    controles_integridad.txt.
    """
    lineas = []
    lineas.append(f"Filas totales: {len(datos)}")
    lineas.append("")
    lineas.append("Repeticiones por celda (estrategia × CF × carga):")
    conteo = datos.groupby([COL_ESTRATEGIA, COL_CF, COL_CARGA]).size()
    lineas.append(conteo.to_string())
    lineas.append("")

    lineas.append(f"Valores distintos de rondas_total: "
                  f"{sorted(datos['rondas_total'].unique().tolist())}")
    lineas.append(f"Valores distintos de utilidad_sana: "
                  f"{sorted(datos['utilidad_sana'].round(6).unique().tolist())}")
    lineas.append("")

    dif = (datos["utilidad_final"] - datos["utilidad_sana"]).abs()
    lineas.append("Máx. |utilidad_final - utilidad_sana| por nivel CF:")
    lineas.append(dif.groupby(datos[COL_CF]).max().round(6).to_string())
    lineas.append("")

    lineas.append(f"Repeticiones censuradas (t_estab_ms = -1): "
                  f"{int(datos['estab_censurada'].sum())}")
    lineas.append("")

    lineas.append("Distribución de problemas_pendientes:")
    lineas.append(datos["problemas_pendientes"].value_counts().sort_index().to_string())
    lineas.append("")

    lineas.append("Celdas con problemas_pendientes > 0:")
    con_problemas = datos[datos["problemas_pendientes"] > 0]
    if len(con_problemas) > 0:
        resumen = con_problemas.groupby(
            [COL_ESTRATEGIA, COL_CF, COL_CARGA]
        )["problemas_pendientes"].agg(["count", "median", "min", "max"])
        lineas.append(resumen.to_string())
    else:
        lineas.append("Ninguna")
    lineas.append("")

    texto = "\n".join(lineas)
    with open(os.path.join(OUT_DIR, "controles_integridad.txt"), "w",
              encoding="utf-8") as f:
        f.write(texto + "\n")

    print("\n=== Controles de integridad ===")
    print(texto)


# ------------------------------------------------------------------
# EJECUCIÓN
# ------------------------------------------------------------------
print("########## OE2 - ACTIVIDAD 1: ESTADÍSTICA DESCRIPTIVA (v3) ##########")

controles_integridad(df)

# Descriptivos de métricas que usan todas las repeticiones
for metrica in METRICAS_TODAS:
    tabla_descriptiva(metrica, df)

# Descriptivos de métricas que excluyen repeticiones censuradas
df_estab_ok = df[~df["estab_censurada"]]
for metrica in METRICAS_SIN_CENSURA:
    tabla_descriptiva(metrica, df_estab_ok)

# ------------------------------------------------------------------
# Tasa de no-estabilización (%): matriz estrategia × CF × carga
# ------------------------------------------------------------------
agrupado = df.groupby([COL_ESTRATEGIA, COL_CF, COL_CARGA])["estab_censurada"]

tasa_censura = (
    (agrupado.mean() * 100)
    .round(2)
    .unstack([COL_CF, COL_CARGA])
    .reindex(index=ORDEN_ESTRATEGIAS)
)

# Reordenar columnas: CF1 primero todas las cargas, luego CF2, etc.
columnas_ordenadas = pd.MultiIndex.from_product(
    [ORDEN_CF, ORDEN_CARGA],
    names=[COL_CF, COL_CARGA]
)
tasa_censura = tasa_censura.reindex(columns=columnas_ordenadas)

tasa_censura.to_csv(os.path.join(OUT_DIR, "tasa_no_estabilizacion.csv"))
tasa_censura.to_latex(os.path.join(OUT_DIR, "tasa_no_estabilizacion.tex"))

conteo_censura = (
    agrupado.sum()
    .astype(int)
    .unstack([COL_CF, COL_CARGA])
    .reindex(index=ORDEN_ESTRATEGIAS)
)
conteo_censura = conteo_censura.reindex(columns=columnas_ordenadas)
conteo_censura.to_csv(os.path.join(OUT_DIR, "repeticiones_no_estabilizadas.csv"))

print("\n=== Tasa de no estabilización (%) por estrategia, CF y carga ===")
print(tasa_censura)

print("\n=== Repeticiones no estabilizadas (conteo) ===")
print(conteo_censura)

# ------------------------------------------------------------------
# Resumen de problemas pendientes por celda
# ------------------------------------------------------------------
resumen_problemas = (
    df.groupby([COL_ESTRATEGIA, COL_CF, COL_CARGA])["problemas_pendientes"]
    .agg(["count", "mean", "median", "max"])
    .round(3)
    .reindex(pd.MultiIndex.from_product(
        [ORDEN_ESTRATEGIAS, ORDEN_CF, ORDEN_CARGA],
        names=[COL_ESTRATEGIA, COL_CF, COL_CARGA]
    ))
)
resumen_problemas.to_csv(os.path.join(OUT_DIR, "resumen_problemas_pendientes.csv"))

print("\n=== Resumen de problemas pendientes por celda ===")
print(resumen_problemas[resumen_problemas["max"] > 0])

print(f"\nArchivos generados en: {OUT_DIR}")