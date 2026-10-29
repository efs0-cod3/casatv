# Casa TV — Nota para la TV

Página web para dibujar una nota con el dedo desde el móvil y enviarla a la TV de casa. La TV puede responder (con un emoji o un texto corto) y la respuesta aparece en el móvil.

## Cómo funciona

La página (`index.html`) llega a la TV por uno de dos caminos:

- **Por el Wi-Fi de casa** (`http`): la propia TV sirve la página. La nota se envía con `POST /note` y el estado se consulta con `GET /status?id=…`.
- **Por internet** (`https`, desplegada en Vercel): la nota pasa por Supabase con las funciones RPC `send_note` y `note_status`. Para poder enviar hace falta la clave de envío que va en el QR de la TV (`#k=…`). La página la guarda en `localStorage` y la quita de la barra de direcciones.

Después de enviar, la página sigue el estado de la nota: **Enviada → Llegó a la TV → Vista → Respondió**. Si se cierra y se vuelve a abrir, retoma la última nota.

## Archivos

| Archivo | Para qué sirve |
| --- | --- |
| `index.html` | La app completa (HTML, CSS y JS en un solo archivo, sin dependencias). |
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
