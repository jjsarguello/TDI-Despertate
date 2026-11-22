package com.example.despertate.core

/**
 * Equivalente de CONFIG_DETECTOR en EspecialistaEnDescanso.py.
 * Todos los umbrales de los 3 estados (Activo, Somnoliento, Peligro) en un solo lugar.
 */
data class DetectorConfig(
    // --- 1. PERCLOS ---
    val perclosVentanaSeg: Double = 30.0,
    val perclosMinObsSeg: Double = 5.0,
    val perclosUmbralSomnolientoPct: Double = 25.0,
    val perclosUmbralPeligroPct: Double = 50.0,
    val perclosPesoOjoCerrado: Double = 1.00,
    val perclosPesoOjoEntrecerrado: Double = 0.25,
    val perclosPesoCabeceo: Double = 0.55,
    val perclosBonoPorBostezoPct: Double = 10.0,
    val perclosTopeBostezosPct: Double = 20.0,

    // --- 2. Estado ACTIVO ---
    val activoOjosPeligroSeg: Double = 1.15,
    val activoOjosEntrecerradosSeg: Double = 0.75,
    val activoCabeceoPeligroSeg: Double = 1.75,
    val activoCabeceoSintomaSeg: Double = 0.85,
    val activoPitchFactor: Double = 1.45,
    val activoPitchDeltaMin: Double = 0.24,
    val activoRollGrados: Double = 24.0,

    // --- 3. Estado SOMNOLIENTO ---
    val somnolientoMemoriaSeg: Double = 30.0,
    val somnolientoOjosPeligroSeg: Double = 0.65,
    val somnolientoCabeceoPeligroSeg: Double = 0.95,
    val somnolientoPitchFactor: Double = 1.28,
    val somnolientoPitchDeltaMin: Double = 0.16,
    val somnolientoRollGrados: Double = 18.0,

    // --- 4. EAR, MAR y recuperación ---
    val earFactorActivo: Double = 0.78,
    val earFactorCerrado: Double = 0.68,
    val bostezoMarUmbral: Double = 0.62,
    val bostezoDuracionSeg: Double = 1.40,
    val parpadeoMinSeg: Double = 0.06,
    val recuperacionActivoSeg: Double = 0.25,
)
