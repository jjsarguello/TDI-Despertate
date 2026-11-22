# Despertate – Detector de somnolencia para Android

Port a Android de `EspecialistaEnDescanso.py` (TDI – UNRC, Grupo 2). La app usa la cámara frontal del celular para vigilar al conductor y suena una alarma si detecta ojos cerrados, cabeceo o fatiga.

Esta guía te lleva de cero a tener la app corriendo en tu celular. Tiempo estimado: 20 a 30 minutos la primera vez (la mayor parte es esperar descargas).

---

## 1. Qué necesitás

| Cosa | Detalle |
|---|---|
| Computadora | Windows, Linux o macOS, con unos 10 GB libres e internet |
| Android Studio | La última versión estable: https://developer.android.com/studio |
| Git | https://git-scm.com/downloads |
| Celular Android | Android 12 o superior, con cámara frontal y cable USB que transmita datos (no uno solo de carga) |

La app fue probada en un Motorola moto g52 con Android 13. También se puede usar el emulador de Android Studio (ver la sección 8), pero con un celular real se prueba mucho mejor.

---

## 2. Bajar el proyecto

Abrí una terminal (en Windows, "Git Bash" o PowerShell) y ejecutá:

```bash
git clone https://github.com/jjsarguello/TDI-Sistema-de-Detecci-n-de-Somnolencia-Drowsiness-detection-.git
cd TDI-Sistema-de-Detecci-n-de-Somnolencia-Drowsiness-detection-
git checkout port
```

El último comando cambia a la rama `port`, que es la que tiene la app Android. Esa carpeta clonada es directamente el proyecto de Android Studio.

---

## 3. Abrir el proyecto en Android Studio

1. Abrí Android Studio.
2. En la pantalla de inicio elegí **Open** (o menú **File > Open**).
3. Seleccioná la carpeta clonada (`TDI-Sistema-de-Detecci-n-de-Somnolencia-Drowsiness-detection-`) y aceptá.
4. Si pregunta "Trust project", elegí **Trust Project**.

---

## 4. Esperar la sincronización de Gradle

Al abrir, Android Studio descarga todo lo que necesita. Abajo a la derecha aparece una barra de progreso.

- La primera vez tarda varios minutos y necesita internet. Esperá a que termine, sin tocar nada.
- Si pide instalar algo (por ejemplo **Android SDK Platform 37**, **Build-Tools** o un **JDK**), aceptá con **Install** o **Accept**.
- Si aparece un cartel con **Sync Now**, apretalo.
- Si dice que el plugin de Android requiere una versión más nueva de Android Studio, actualizalo desde **Help > Check for Updates** y volvé a abrir el proyecto.

Sabés que terminó cuando la pestaña **Build** de abajo muestra "BUILD SUCCESSFUL" o desaparece la barra de progreso.

El modelo de detección de rostro ya viene incluido en el repositorio (`app/src/main/assets/face_landmarker.task`), no hace falta bajarlo aparte.

---

## 5. Preparar el celular

Hacelo una sola vez.

1. Entrá a **Ajustes > Acerca del teléfono**.
2. Tocá 7 veces seguidas **Número de compilación** hasta que diga "Ahora eres desarrollador".
3. Volvé a **Ajustes > Sistema > Opciones de desarrollador** y activá **Depuración por USB**.
4. Conectá el celular a la computadora con el cable.
5. En el celular aparece "¿Permitir depuración por USB?". Marcá **Permitir siempre desde este equipo** y tocá **Permitir**.

En algunos celulares hay que cambiar el modo del USB a **Transferencia de archivos** (se elige desde la notificación del cable).

---

## 6. Ejecutar la app

1. En la barra de arriba de Android Studio, al lado del botón verde, tiene que aparecer el nombre de tu celular. Si no aparece, mirá la sección 8.
2. Apretá el botón verde **Run** (el triángulo, o la tecla `Shift + F10`).
3. Esperá a que compile e instale. Es más lento la primera vez.
4. La app se abre sola en el celular. Cuando pida permisos, tocá **Permitir** en:
   - **Cámara** (obligatorio).
   - **Notificaciones** (recomendado: muestra el aviso de que la app está vigilando y el botón Detener).

---

## 7. Cómo usar y probar la app

### Colocación del celular
- Ponelo frente a tu cara, a la altura de los ojos, a unos 40 a 70 cm, mirando de frente.
- Buena luz sobre el rostro. Evitá una ventana fuerte detrás de vos.

### Calibración (primeros 3 segundos)
Al empezar, la barra de arriba dice **CALIBRANDO** con un porcentaje. Mirá de frente con los ojos abiertos y relajado, sin hablar. La app aprende cómo es tu cara en reposo. Si querés repetirla, tocá **Recalibrar**.

### Estados
| Color y texto | Significado |
|---|---|
| Celeste: CALIBRANDO | Aprendiendo tu postura y tus ojos |
| Verde: Activo | Todo normal |
| Naranja: MODO SOMNOLIENTO | Señales de fatiga (bostezos, cabeceo leve, PERCLOS alto). Sin alarma |
| Rojo: PELIGRO | Ojos cerrados mucho tiempo o cabeceo fuerte. **Suena la alarma** |
| Naranja: SIN CONDUCTOR | No se ve ningún rostro |

### Botones (panel de la derecha)
| Botón | Qué hace |
|---|---|
| **Recalibrar** | Repite la calibración de 3 segundos |
| **EMA: ON/OFF** | Activa o desactiva el filtro de suavizado temporal |
| **CLAHE: ON/OFF** | Preprocesamiento de imagen (gamma adaptativo + CLAHE). Mejora la detección con mala iluminación |
| **Lentes: AUTO/ON/OFF** | Detección de anteojos de sol. AUTO la hace sola; ON y OFF la fuerzan |
| **Datos: SI/NO** | Muestra u oculta el panel de métricas |
| **Segundo plano** | Pasa a una ventanita flotante para usar otras apps (por ejemplo Google Maps) |
| **Probar alarma** | Suena la alarma 3 segundos, para chequear el volumen |
| **Detener** | Apaga cámara, detección y alarma |

### Lista de pruebas sugeridas
1. **Ojos cerrados:** cerrá los ojos 2 segundos. Debe pasar a PELIGRO y sonar la alarma. Al abrirlos se apaga.
2. **Cabeceo:** bajá la cabeza como si te durmieras. Debe saltar la alarma.
3. **Bostezo:** abrí bien la boca un par de segundos. Cuenta un bostezo y marca fatiga, pero no suena la alarma.
4. **Parpadeo normal:** mirá al frente 30 segundos. No debe haber falsas alarmas.
5. **Anteojos de sol:** con **Lentes: AUTO**, ponete anteojos oscuros. Debe decir "[Lentes de Sol]" y los ojos se dibujan en violeta. Al sacártelos vuelve a la normalidad.
6. **Mala luz:** probá con luz fuerte de costado y comparalo con **CLAHE** prendido y apagado.
7. **Segundo plano:** abrí Google Maps y tocá **Segundo plano** (o salí de la app con el gesto de inicio). Queda una ventana flotante y la app sigue vigilando.
8. **Pantalla bloqueada:** bloqueá el celular unos segundos. La detección y la alarma siguen. Para volver, tocá la notificación.
9. **Rotación:** girá el celular. La cara se tiene que seguir analizando derecha.
10. **Apagar:** tocá **Detener** (o el botón **Detener** de la notificación).

### Volumen de la alarma
La alarma usa el volumen de **ALARMA** del celular, no el de multimedia. Con la app abierta, los botones de volumen del celular controlan ese volumen. Si el panel de métricas dice **(BAJO)**, subilo. Con el modo **No molestar** activado puede no sonar.

---

## 8. Si algo falla

**El celular no aparece en Android Studio**
- Probá otro cable (tiene que ser de datos) u otro puerto USB.
- Chequeá que **Depuración por USB** esté activada y que aceptaste el cartel en el celular.
- En la terminal ejecutá `adb devices`. Si dice `unauthorized`, desconectá el cable, en **Opciones de desarrollador** tocá **Revocar autorizaciones de depuración USB**, reconectá y aceptá de nuevo.

**La sincronización de Gradle falla**
- Revisá tu conexión a internet y tocá **File > Sync Project with Gradle Files** (el ícono del elefante).
- Si dice que falta una versión del SDK o del JDK, instalala desde el aviso que propone Android Studio.

**La pantalla se queda en "Esperando datos..."**
Debajo aparece en qué paso se trabó (servicio, detector o cámara). Sacale una captura y mandásela a quien te pasó el proyecto.

**Dice "No se pudo iniciar MediaPipe"**
Verificá que exista `app/src/main/assets/face_landmarker.task`. Si falta, descargalo de https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task y guardalo en esa carpeta con ese nombre exacto. La app usa la GPU y, si falla, pasa sola a la CPU.

**La alarma no suena**
Tocá **Probar alarma**, subí el volumen de alarma y revisá que no esté activado el modo No molestar.

**En segundo plano se corta la cámara (algunos Motorola, Xiaomi, Samsung)**
Desactivá la optimización de batería para la app: **Ajustes > Apps > Despertate > Batería > Sin restricciones**.

**Usar el emulador en vez de un celular**
En el **Device Manager** de Android Studio creá un dispositivo con Android 12 o superior. En **Extended controls > Camera**, elegí **Webcam0** como cámara frontal. Va a andar más lento que en un celular real.

---

## 9. Correr los tests (opcional)

Son 17 tests del núcleo de detección (métricas, calibración, alarma, lentes de sol). No necesitan celular.

- En Android Studio: clic derecho sobre `app/src/test` y **Run Tests**.
- O por terminal, desde la carpeta del proyecto:
  - Linux o macOS: `./gradlew test`
  - Windows: `gradlew.bat test`

---

## 10. Qué hay en el proyecto

```
(raíz del repositorio, rama port)
├── app/src/main/
│   ├── java/com/example/despertate/
│   │   ├── MainActivity.kt          Pantalla, botones y ventana flotante
│   │   ├── DetectionService.kt      Cámara, detección y alarma en primer plano
│   │   ├── FaceLandmarkerHelper.kt  Detector de rostro (MediaPipe)
│   │   ├── ImagePreprocessor.kt     Gamma adaptativo + CLAHE
│   │   ├── OverlayView.kt           Dibuja el cuadro y los puntos de la cara
│   │   ├── AlarmPlayer.kt           Reproduce la alarma
│   │   └── core/                    Lógica pura, sin Android (la que prueban los tests)
│   │       ├── FaceMetrics.kt       EAR, MAR, pitch/roll/yaw
│   │       ├── PerclosTracker.kt    PERCLOS con ventana deslizante
│   │       ├── DrowsinessEngine.kt  Calibración y máquina de estados
│   │       ├── SunglassesDetector.kt Detección de lentes de sol
│   │       ├── LandmarkMapper.kt    Puntos de MediaPipe a puntos tipo dlib
│   │       └── DetectorConfig.kt    Umbrales
│   ├── assets/face_landmarker.task  Modelo de MediaPipe
│   └── res/raw/alarma.wav           Sonido de la alarma
└── app/src/test/                    Tests del núcleo
```

### Cómo se relaciona con el script de Python
- Se usan las mismas métricas y fórmulas: EAR, MAR, PERCLOS ponderado, postura 3D de la cabeza, filtro EMA independiente de los FPS y calibración personal.
- Cambia el detector: en Python se usa dlib con 68 puntos; en Android, MediaPipe con 478 puntos, de los cuales se toman los equivalentes a los 68 (`LandmarkMapper.kt`).
- Se mantienen las partes de TDI: gamma adaptativo, CLAHE y detección radiométrica de lentes de sol.
- No se portó: cámara IP Foscam, PTZ, visión infrarroja, seguimiento multi-rostro ni selección de piloto.

### Privacidad
La app no pide permiso de internet y no guarda imágenes ni video. Todo el procesamiento se hace en el celular.
