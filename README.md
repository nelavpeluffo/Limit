# AppCel

App para Android que limita a **15 minutos por día**:

- la pestaña **Reels** de Instagram
- la pestaña **Para ti** de X (Twitter)

El resto de Instagram y X (feed, historias, mensajes, la pestaña *Siguiendo*) se puede usar sin límite.

## Cómo funciona

- Mientras estás en Reels o en Para ti, arriba de la pantalla aparece una pastillita con el tiempo que te queda (se puede ocultar).
- Cuando falta 1 minuto, te avisa.
- Al llegar a 15 minutos te saca: en Instagram te lleva al Inicio y en X te pasa a *Siguiendo*. Si insistís, te manda a la pantalla de inicio del teléfono.
- El contador se reinicia a la medianoche.

## Instalarla

1. Descargá **AppCel.apk** desde [la última versión](../../releases/latest).
2. Abrí el archivo. Si Android pregunta, permití instalar apps desde esa fuente (Chrome o Archivos).
3. Abrí AppCel y seguí los pasos de la pantalla para activarla en **Accesibilidad**.
4. Si Android dice **«Configuración restringida»**: en AppCel tocá «Abrir info de la app» → menú ⋮ arriba a la derecha → «Permitir configuración restringida». Después volvé a activarla en Accesibilidad.

## Privacidad

La app no tiene permiso de internet. Solo busca los botones de las pestañas (Reels en Instagram; Para ti / Siguiendo en X) para saber cuál está seleccionada. No recorre el resto de la pantalla, no lee publicaciones ni mensajes y no guarda nada de lo que ves. El modo diagnóstico tampoco guarda textos: solo si esos botones están seleccionados.

## Compilación

Cada cambio que se sube a `main` se compila solo con GitHub Actions y se publica en *Releases*.
