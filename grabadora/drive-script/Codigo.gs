/**
 * Conector entre la app "Grabadora de clases" y tu Google Drive.
 *
 * Este script vive en TU cuenta de Google. La app le pide permiso para subir
 * un audio y el script le abre una subida directa a tu carpeta de Drive.
 * Nadie más tiene acceso a tu Drive: solo quien tenga el enlace secreto
 * de esta aplicación web puede subir archivos a esa carpeta.
 */

// Nombre de la carpeta de Drive donde se guardan las clases (se crea sola).
const NOMBRE_CARPETA = 'Grabaciones de clase';

function doPost(e) {
  try {
    const peticion = JSON.parse(e.postData.contents);
    const carpeta = obtenerCarpeta_();

    if (peticion.action === 'ping') {
      return responder_({ ok: true, folder: carpeta.getName() });
    }

    if (peticion.action === 'start') {
      const metadatos = {
        name: String(peticion.name || 'Clase.m4a'),
        mimeType: String(peticion.mimeType || 'audio/mp4'),
        parents: [carpetaDestino_(carpeta, peticion.subfolder).getId()],
      };
      const respuesta = UrlFetchApp.fetch(
        'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true',
        {
          method: 'post',
          contentType: 'application/json; charset=UTF-8',
          payload: JSON.stringify(metadatos),
          headers: {
            Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
            'X-Upload-Content-Type': metadatos.mimeType,
            'X-Upload-Content-Length': String(peticion.size || 0),
          },
          muteHttpExceptions: true,
        }
      );
      if (respuesta.getResponseCode() !== 200) {
        return responder_({ error: 'Drive no aceptó la subida: ' + respuesta.getContentText() });
      }
      const cabeceras = respuesta.getAllHeaders();
      const url = cabeceras['Location'] || cabeceras['location'];
      return responder_({ uploadUrl: url });
    }

    return responder_({ error: 'Acción desconocida' });
  } catch (err) {
    return responder_({ error: String(err) });
  }
}

function doGet() {
  return responder_({ ok: true, folder: obtenerCarpeta_().getName() });
}

/** Ejecuta esta función una vez desde el editor para dar los permisos. */
function autorizar() {
  const carpeta = obtenerCarpeta_();
  UrlFetchApp.fetch('https://www.googleapis.com/drive/v3/about?fields=user', {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
  });
  Logger.log('Todo listo. Carpeta: ' + carpeta.getUrl());
}

/** Subcarpeta por asignatura (si la app la manda); si no, la carpeta principal. */
function carpetaDestino_(carpeta, nombre) {
  nombre = String(nombre || '').trim().slice(0, 80);
  if (!nombre) return carpeta;
  const encontradas = carpeta.getFoldersByName(nombre);
  return encontradas.hasNext() ? encontradas.next() : carpeta.createFolder(nombre);
}

function obtenerCarpeta_() {
  const encontradas = DriveApp.getFoldersByName(NOMBRE_CARPETA);
  return encontradas.hasNext() ? encontradas.next() : DriveApp.createFolder(NOMBRE_CARPETA);
}

function responder_(objeto) {
  return ContentService.createTextOutput(JSON.stringify(objeto))
    .setMimeType(ContentService.MimeType.JSON);
}
