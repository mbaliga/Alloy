// VTK XML unstructured grid (.vtu): a writer (ASCII) and a reader (ASCII and inline base64 "binary"; appended data is refused with a
// message). Plain functions with no imports and no Node-only APIs: this file is also concatenated into the Forge package
// `xyz.mdhv.runner` by forge/tools/bundle-ext.mjs, so the runner and Forge read and write the same dialect.
//
// Units of the files the runner hands to Forge: points in millimetres, displacement in mm, stress in MPa, temperature in degrees C
// (the runner converts what Elmer writes, which is SI, before it hands the file over).
export const VTK = { VERTEX: 1, LINE: 3, TRIANGLE: 5, QUAD: 9, TETRA: 10, HEXAHEDRON: 12 };
const NPTS = { 1: 1, 3: 2, 5: 3, 9: 4, 10: 4, 12: 8 };

/**
 * mesh: { points: ArrayLike<number> (3 per point), cells: { types: Uint8Array, connectivity: Int32Array, offsets: Int32Array (end index of each cell) },
 *         pointData: { name: { ncomp: 1|3|6, data: ArrayLike<number> } }, cellData?: same }
 */
export function writeVtu(mesh) {
  const np = mesh.points.length / 3, nc = mesh.cells.types.length, num = (a) => Array.from(a, (v) => (Number.isInteger(v) ? String(v) : (+v).toPrecision(9))).join(' ');
  const arr = (type, name, ncomp, data) => `<DataArray type="${type}" Name="${name}" NumberOfComponents="${ncomp}" format="ascii">${num(data)}</DataArray>`;
  const dataBlock = (tag, d) => `<${tag}>${Object.entries(d || {}).map(([name, f]) => arr('Float64', name, f.ncomp, f.data)).join('')}</${tag}>`;
  return `<?xml version="1.0"?>\n<VTKFile type="UnstructuredGrid" version="1.0" byte_order="LittleEndian"><UnstructuredGrid><Piece NumberOfPoints="${np}" NumberOfCells="${nc}">`
    + `<Points>${arr('Float64', 'Points', 3, mesh.points)}</Points>`
    + `<Cells>${arr('Int32', 'connectivity', 1, mesh.cells.connectivity)}${arr('Int32', 'offsets', 1, mesh.cells.offsets)}${arr('UInt8', 'types', 1, mesh.cells.types)}</Cells>`
    + dataBlock('PointData', mesh.pointData) + dataBlock('CellData', mesh.cellData) + `</Piece></UnstructuredGrid></VTKFile>\n`;
}

const B64 = (() => { const t = new Uint8Array(256).fill(255); 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.split('').forEach((c, i) => { t[c.charCodeAt(0)] = i; }); return t; })();
function b64decode(s) {
  s = s.replace(/[^A-Za-z0-9+/]/g, ''); const n = Math.floor(s.length * 3 / 4), out = new Uint8Array(n); let o = 0, acc = 0, bits = 0;
  for (let i = 0; i < s.length; i++) { acc = (acc << 6) | B64[s.charCodeAt(i)]; bits += 6; if (bits >= 8) { bits -= 8; if (o < n) out[o++] = (acc >> bits) & 255; acc &= (1 << bits) - 1; } }
  return out;
}
const TYPES = { Float64: [Float64Array, 8], Float32: [Float32Array, 4], Int32: [Int32Array, 4], UInt8: [Uint8Array, 1], Int64: [null, 8], UInt32: [Uint32Array, 4], UInt64: [null, 8], Int8: [Int8Array, 1], Int16: [Int16Array, 2], UInt16: [Uint16Array, 2] };

function readArray(attrs, body, headerType) {
  const [Ctor, size] = TYPES[attrs.type] || [];
  if (!size) throw new Error(`VTU: unsupported DataArray type "${attrs.type}"`);
  const fmt = attrs.format || 'ascii';
  if (fmt === 'ascii') {
    const toks = body.trim() ? body.trim().split(/\s+/) : [], out = Ctor ? new Ctor(toks.length) : new Float64Array(toks.length);
    for (let i = 0; i < toks.length; i++) out[i] = +toks[i];
    return out;
  }
  if (fmt === 'binary') { // inline base64: one header (the byte count; UInt32 or UInt64) then the raw bytes, uncompressed
    const raw = b64decode(body), hs = headerType === 'UInt64' ? 8 : 4, dv = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
    const nbytes = hs === 8 ? Number(dv.getBigUint64(0, true)) : dv.getUint32(0, true), data = raw.slice(hs, hs + nbytes), view = new DataView(data.buffer);
    const n = Math.floor(nbytes / size), out = Ctor && attrs.type !== 'Int64' && attrs.type !== 'UInt64' ? new Ctor(n) : new Float64Array(n);
    for (let i = 0; i < n; i++) {
      const o = i * size; const t = attrs.type;
      out[i] = t === 'Float64' ? view.getFloat64(o, true) : t === 'Float32' ? view.getFloat32(o, true) : t === 'Int32' ? view.getInt32(o, true) : t === 'UInt32' ? view.getUint32(o, true)
        : t === 'UInt8' ? view.getUint8(o) : t === 'Int8' ? view.getInt8(o) : t === 'Int16' ? view.getInt16(o, true) : t === 'UInt16' ? view.getUint16(o, true) : t === 'Int64' ? Number(view.getBigInt64(o, true)) : Number(view.getBigUint64(o, true));
    }
    return out;
  }
  throw new Error(`VTU: DataArray format "${fmt}" is not supported (use ASCII or inline base64 output; Elmer: "Ascii Output = Logical True")`);
}

const attrsOf = (s) => { const o = {}; s.replace(/([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"/g, (_, k, v) => { o[k] = v; return ''; }); return o; };
const arraysIn = (xml, headerType) => {
  const out = []; const re = /<DataArray\b([^>]*?)(?:\/>|>([\s\S]*?)<\/DataArray>)/g; let m;
  while ((m = re.exec(xml))) { const a = attrsOf(m[1]); if (a.format === 'appended') throw new Error('VTU: appended data is not supported (use ASCII or inline base64 output; Elmer: "Ascii Output = Logical True")'); out.push({ name: a.Name || '', ncomp: +(a.NumberOfComponents || 1), data: readArray(a, m[2] || '', headerType) }); }
  return out;
};

/** Parse a .vtu (string or bytes) into { points: Float64Array, cells: { types, connectivity, offsets }, pointData, cellData }. Only the first piece is read. */
export function parseVtu(input) {
  const text = typeof input === 'string' ? input : new TextDecoder().decode(input);
  if (!/<VTKFile[^>]*type="UnstructuredGrid"/.test(text)) throw new Error('VTU: not an UnstructuredGrid file');
  const headerType = (/<VTKFile[^>]*header_type="(\w+)"/.exec(text) || [])[1] || 'UInt32';
  if (/<DataArray[^>]*(compressor|vtkZLibDataCompressor)/.test(text) || /<VTKFile[^>]*compressor=/.test(text)) throw new Error('VTU: compressed data is not supported');
  const piece = /<Piece\b([^>]*)>([\s\S]*?)<\/Piece>/.exec(text); if (!piece) throw new Error('VTU: no <Piece>');
  const pa = attrsOf(piece[1]), np = +pa.NumberOfPoints, nc = +pa.NumberOfCells, body = piece[2];
  const sec = (tag) => { const m = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`).exec(body); return m ? m[1] : ''; };
  const pts = arraysIn(sec('Points'), headerType)[0]; if (!pts || pts.data.length !== 3 * np) throw new Error('VTU: the points do not match NumberOfPoints');
  const cellArr = Object.fromEntries(arraysIn(sec('Cells'), headerType).map((a) => [a.name, a.data]));
  const types = cellArr.types, offsets = cellArr.offsets, connectivity = cellArr.connectivity;
  if (!types || !offsets || !connectivity || types.length !== nc || offsets.length !== nc) throw new Error('VTU: the cells are incomplete');
  const grab = (tag) => Object.fromEntries(arraysIn(sec(tag), headerType).map((a) => [a.name, { ncomp: a.ncomp, data: Float64Array.from(a.data) }]));
  const pointData = grab('PointData'), cellData = grab('CellData');
  for (const [k, f] of Object.entries(pointData)) if (f.data.length !== f.ncomp * np) throw new Error(`VTU: point field "${k}" has ${f.data.length} values for ${np} points`);
  return { points: Float64Array.from(pts.data), cells: { types: Uint8Array.from(types), connectivity: Int32Array.from(connectivity), offsets: Int32Array.from(offsets) }, pointData, cellData };
}

/** Number of points of a VTK cell type, or 0 when unknown. */
export const cellPointCount = (t) => NPTS[t] || 0;
