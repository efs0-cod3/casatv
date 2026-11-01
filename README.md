# Casa TV — Nota para la TV

Página web para dibujar una nota con el dedo desde el móvil y enviarla a la TV de casa. La TV puede responder (con un emoji o un texto corto) y la respuesta aparece en el móvil.

## Cómo funciona

La página (`index.html`) llega a la TV por uno de dos caminos:

- **Por el Wi-Fi de casa** (`http`): la propia TV sirve la página. La nota se envía con `POST /note` y el estado se consulta con `GET /status?id=…`.
- **Por internet** (`https`, desplegada en Vercel): la nota pasa por Supabase con las funciones RPC `send_note` y `note_status`. Para poder enviar hace falta la clave de envío que va en el QR de la TV (`#k=…`). La página la guarda en `localStorage` y la quita de la barra de direcciones.

La nota puede ser un **dibujo** o un **texto escrito**. En modo «Escribir», el texto se dibuja centrado en el color elegido, con la letra más grande que quepa, y se envía como imagen igual que un dibujo. Arriba, un indicador muestra si la TV está encendida o desde cuándo está apagada (`tv_status`).

Si la casa tiene **varias TVs** (tabla `tvs`), arriba aparece un botón por TV más «Todas», y el indicador muestra el estado de la elegida. El QR de cada TV puede traer `&tv=<nombre>` para dejarla elegida de entrada.

Después de enviar, la página sigue el estado de la nota: **Enviada → Llegó a la TV → Vista → Respondió**. Si se cierra y se vuelve a abrir, retoma la última nota.

## App en la pantalla de inicio

La misma página se puede instalar como app: tiene su ícono, abre a pantalla completa sin la barra de Safari y se actualiza sola con cada despliegue en Vercel (el service worker no guarda nada en caché).

**En el iPhone:** escanea el QR de la TV, toca Compartir → «Añadir a pantalla de inicio» y abre Casa TV desde el ícono.

- La app instalada no comparte datos con Safari, así que en el navegador la clave se queda en la dirección (`#k=…`) para que el ícono la lleve. Ya dentro de la app instalada, se quita.
- Al tocar «Enviar a la TV» la primera vez, la app pide permiso para notificaciones (en iPhone, solo desde la app instalada, iOS 16.4 o posterior). Si lo das, cuando la TV responda te llega «La TV respondió ❤️» aunque tengas la app cerrada.

### Cómo llegan las notificaciones

1. Tras enviar, el móvil se suscribe a Web Push y guarda la suscripción en su nota (`note_push`).
2. Cuando la TV responde (`tv_answer`), el disparador `notes_reply_push` llama a la función `notify-reply` con `pg_net`.
3. `notify-reply` firma el aviso con la clave VAPID privada (guardada en Supabase Vault) y lo manda una vez por respuesta. Con varias TVs, dice cuál respondió («Sala respondió ❤️»).

Con varias TVs, `send_note` crea una nota por TV y devuelve el id del grupo; `note_push` suscribe todas las notas del grupo.

## Archivos

| Archivo | Para qué sirve |
| --- | --- |
| `index.html` | La app completa (HTML, CSS y JS en un solo archivo, sin dependencias). |
| `manifest.webmanifest` | Nombre, colores e íconos de la app instalada. |
| `sw.js` | Service worker: muestra las notificaciones y abre la app al tocarlas. |
| `icons/` | Ícono en SVG y PNG (192, 512 y 180 para el iPhone). |
| `supabase/` | Migración SQL y función `notify-reply` de las notificaciones. |
| `vercel.json` | Cabeceras para Vercel: `Cache-Control: no-cache` y `Referrer-Policy: no-referrer`, para que el enlace con la clave no se filtre por el referrer. |

## Desplegar en Vercel

1. Importa este repositorio en Vercel.
2. No necesita comando de build ni framework: es un sitio estático.
3. Abre la página escaneando el QR que muestra la TV.

## Desarrollo local

```bash
python3 -m http.server 8000
```

Abre `http://localhost:8000`. En `http` la página intenta hablar con la TV por `/note`, así que para probar el envío real hace falta el servidor de la TV.
