# Sistema de Detección de Somnolencia en Tiempo Real (`EspecialistaEnDescanso.py`)

**UNRC – Tratamiento Digital de Imágenes (TDI)**  
**Grupo 2:** Pérez Rossi, Tomás | Argüello, Juan Salvador

Compatible con **Windows, Linux y macOS** (Python 3.9 a 3.13).

---

## ¿Qué archivos incluye el proyecto?
- **`EspecialistaEnDescanso.py`**: Sistema principal de detección de somnolencia y fatiga (EAR, MAR, PERCLOS, postura cefálica Pitch/Roll/Yaw, preprocesamiento Gamma+CLAHE, filtro temporal EMA y calibración automática).
- **`requirements.txt`**: Dependencias del proyecto.
- **`alarma.wav`**: Archivo de alerta sonora (si no se encuentra, el programa lo sintetiza automáticamente).
- **`shape_predictor_68_face_landmarks.dat`**: Modelo de 68 landmarks de `dlib` (pesa ~96 MB; **el programa lo descarga y descomprime automáticamente** la primera vez que se ejecuta si no está en la carpeta).
- **`Explicacion_EspecialistaEnDescanso.txt`**: Documentación detallada de cada módulo, fórmula y su utilidad en TDI.

---

## Cómo ejecutar el programa en cualquier computadora

### Opción A: En Windows (PowerShell o CMD)
1. Abrir una terminal (**PowerShell** o **CMD**) dentro de la carpeta del proyecto.
2. Crear y activar un entorno virtual (recomendado):
   ```powershell
   python -m venv .venv
   .venv\Scripts\activate
   ```
3. Instalar las librerías (usa `dlib-bin` precompilado, no requiere instalar CMake ni C++):
   ```powershell
   pip install -r requirements.txt
   ```
4. Ejecutar el programa:
   ```powershell
   python EspecialistaEnDescanso.py
   ```

---

### Opción B: En Linux o macOS
1. Abrir una terminal dentro de la carpeta del proyecto.
2. Crear y activar un entorno virtual:
   ```bash
   python3 -m venv .venv
   source .venv/bin/activate
   ```
3. Instalar las librerías:
   ```bash
   pip install -r requirements.txt
   ```
4. Ejecutar el programa:
   ```bash
   python3 EspecialistaEnDescanso.py
   ```

---

## Controles interactivos dentro del programa
- **Calibración inicial automática:** Al iniciar, mirá normalmente a la cámara durante **3 segundos** para que el sistema calibre los umbrales según el tamaño de tus ojos y tu postura.
- **Tecla `c`**: Volver a calibrar los umbrales (3 segundos).
- **Tecla `e`**: Activar / Desactivar la corrección de iluminación en el rostro (**Gamma Adaptativo + CLAHE**).
- **Tecla `f`**: Activar / Desactivar el filtro de estabilidad temporal (**EMA**).
- **Tecla `q` o `ESC`**: Salir del programa.
