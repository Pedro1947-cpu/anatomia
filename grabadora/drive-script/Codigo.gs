/**
 * Conector entre la app "Grabadora de clases" y tu Google Drive.
 *
 * Este script vive en TU cuenta de Google. La app le pide permiso para subir
 * un audio y el script le abre una subida directa a tu carpeta de Drive.
 * Nadie más tiene acceso a tu Drive: solo quien tenga el enlace secreto
 * de esta aplicación web puede subir archivos a esa carpeta.
 */

// Carpeta "Medicina 1º" con una carpeta por asignatura.
const CARPETA_ASIGNATURAS_ID = '1YTfzK1DmJ_hE_3troD4kEekfiBU-Jc8x';

// Palabra del título de la clase en el calendario → carpeta de la asignatura.
// (Da igual mayúsculas y tildes. Añade aquí más asignaturas cuando las tengas.)
const ASIGNATURAS = {
  'Bioquímica': 'Bioquímica',
  'Anatomía': 'Anatomía',
  'Biología': 'Biología',
  'Psicología': 'Psicología',
  'Documentación': 'DocHisTeoMetMed',
  'Historia': 'DocHisTeoMetMed',
};

// Dentro de cada asignatura, los audios van a esta subcarpeta ('' = directamente en la asignatura).
const SUBCARPETA_AUDIOS = 'Grabaciones';

// Si una grabación no encaja con ninguna asignatura (o la grabas a mano), va aquí (se crea sola).
const NOMBRE_CARPETA = 'Grabaciones de clase';

function doPost(e) {
  try {
    const peticion = JSON.parse(e.postData.contents);
    const carpeta = obtenerCarpeta_();

    if (peticion.action === 'ping') {
      return responder_({ ok: true, folder: DriveApp.getFolderById(CARPETA_ASIGNATURAS_ID).getName() });
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

/** Elige la carpeta según la asignatura (el título de la clase). */
function carpetaDestino_(carpetaGeneral, titulo) {
  titulo = String(titulo || '').trim();
  if (!titulo) return carpetaGeneral;
  const t = normalizar_(titulo);
  for (const palabra in ASIGNATURAS) {
    if (t.indexOf(normalizar_(palabra)) === -1) continue;
    const asignatura = buscarSubcarpeta_(DriveApp.getFolderById(CARPETA_ASIGNATURAS_ID), ASIGNATURAS[palabra]);
    if (!asignatura) break;
    return SUBCARPETA_AUDIOS ? subcarpeta_(asignatura, SUBCARPETA_AUDIOS) : asignatura;
  }
  return subcarpeta_(carpetaGeneral, titulo.slice(0, 80));
}

/** Busca una subcarpeta sin fijarse en mayúsculas, tildes ni espacios sobrantes. */
function buscarSubcarpeta_(padre, nombre) {
  const buscado = normalizar_(nombre);
  const it = padre.getFolders();
  while (it.hasNext()) {
    const f = it.next();
    if (normalizar_(f.getName()) === buscado) return f;
  }
  return null;
}

function subcarpeta_(padre, nombre) {
  return buscarSubcarpeta_(padre, nombre) || padre.createFolder(nombre);
}

function normalizar_(s) {
  return String(s).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
}

/** Prueba desde el editor: muestra a qué carpeta iría cada clase. */
function probarCarpetas() {
  const general = obtenerCarpeta_();
  ['Bioquímica Básica', 'Anatomía Humana I (prácticas)', 'Documentación, Historia, Teoría y Método de la Medicina',
   'Biología Celular', 'Psicología (prácticas)', ''].forEach(function (t) {
    const f = carpetaDestino_(general, t);
    Logger.log((t || '(grabación manual)') + '  →  ' + f.getName() + '  (' + f.getUrl() + ')');
  });
}

function obtenerCarpeta_() {
  const encontradas = DriveApp.getFoldersByName(NOMBRE_CARPETA);
  return encontradas.hasNext() ? encontradas.next() : DriveApp.createFolder(NOMBRE_CARPETA);
}

function responder_(objeto) {
  return ContentService.createTextOutput(JSON.stringify(objeto))
    .setMimeType(ContentService.MimeType.JSON);
}
