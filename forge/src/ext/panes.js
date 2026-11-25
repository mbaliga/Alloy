// Package panes: an HTML file from the package shown in a sandboxed iframe.
//
//   sandbox="allow-scripts"            no same-origin (opaque origin), no forms, no popups, no top navigation, no downloads
//   CSP in the document               default-src 'none': inline script and style only, data: images. No network of any kind.
//   <script src> / <link href> that point at files inside the package are inlined first, so panes can be several files.
//
// The pane can do exactly one thing: send messages to its own package Worker through the `alloy` bridge below. The Worker has the
// permissions; the pane has none. Replies and pushes come back as messages. Pure DOM + postMessage: no Forge internals.

const td = new TextDecoder();

export const PANE_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; media-src 'none'; form-action 'none'; base-uri 'none'";

const BRIDGE = `<script>(function(){var pend={},lst={},n=0;window.alloy={call:function(m,d){return new Promise(function(res,rej){var id=++n;pend[id]={res:res,rej:rej};parent.postMessage({alloyExt:1,t:'call',id:id,m:String(m),d:d},'*')})},on:function(e,f){(lst[e]=lst[e]||[]).push(f)}};addEventListener('message',function(ev){if(ev.source!==parent)return;var d=ev.data;if(!d||d.alloyExt!==1)return;if(d.t==='res'){var p=pend[d.id];if(!p)return;delete pend[d.id];d.ok?p.res(d.v):p.rej(new Error(d.e))}else if(d.t==='emit'){(lst[d.e]||[]).slice().forEach(function(f){try{f(d.v)}catch(x){console.error(x)}})}});parent.postMessage({alloyExt:1,t:'ready'},'*')})();<\/script>`;

const esc = (s) => s.replace(/<\/(script|style)/gi, '<\\/$1');

/** Build the srcdoc for a pane from the package files. Exported for tests. */
export function paneDocument(files, file) {
  const raw = files.get(file);
  if (!raw) throw new Error(`pane file "${file}" is not in the package`);
  const dir = file.includes('/') ? file.slice(0, file.lastIndexOf('/') + 1) : '';
  const get = (p) => { const f = files.get(dir + p.replace(/^\.\//, '')); return f ? esc(td.decode(f)) : null; };
  let html = td.decode(raw)
    .replace(/<script\b([^>]*?)\bsrc=["']([^"':]+)["']([^>]*)>\s*<\/script>/gi, (m, a, src, b) => { const t = get(src); return t == null ? '' : `<script${a}${b}>${t}</script>`; })
    .replace(/<link\b[^>]*\brel=["']stylesheet["'][^>]*\bhref=["']([^"':]+)["'][^>]*>/gi, (m, href) => { const t = get(href); return t == null ? '' : `<style>${t}</style>`; });
  const meta = `<meta http-equiv="Content-Security-Policy" content="${PANE_CSP}">`;
  return /<head[^>]*>/i.test(html) ? html.replace(/<head[^>]*>/i, (m) => m + meta + BRIDGE) : `<!doctype html><html><head><meta charset="utf-8">${meta}${BRIDGE}</head><body>${html}</body></html>`;
}

/**
 * @param {{ host: ExtHost, getPkg:(extId)=>pkg|null, getTheme:()=>object }} o
 * Panes are keyed `${extId}/${paneId}`. An iframe is created once and kept, so a pane keeps its state when the sheet is hidden.
 */
export function createPaneHost({ host, getPkg, getTheme = () => ({}) }) {
  const panes = new Map();
  const reply = (e, m) => { try { e.frame.contentWindow.postMessage({ alloyExt: 1, ...m }, '*'); } catch (_) {} };

  addEventListener('message', async (ev) => {
    const d = ev.data;
    if (!d || d.alloyExt !== 1) return;
    const e = [...panes.values()].find((p) => p.frame.contentWindow === ev.source); // only our own iframes, matched by window identity
    if (!e) return;
    if (d.t === 'ready') { reply(e, { t: 'emit', e: 'theme', v: getTheme() }); return; }
    if (d.t !== 'call') return;
    const pkg = getPkg(e.extId);
    if (!pkg) return reply(e, { t: 'res', id: d.id, ok: false, e: 'package is not installed' });
    try { reply(e, { t: 'res', id: d.id, ok: true, v: await host.invoke(pkg, 'pane', e.paneId, { method: String(d.m), data: d.d }, { timeoutMs: 60000 }) }); }
    catch (err) { reply(e, { t: 'res', id: d.id, ok: false, e: String(err.message || err) }); }
  });

  return {
    /** Mount (or return) the iframe for a pane. */
    mount(extId, paneDef, container) {
      const key = `${extId}/${paneDef.id}`; let e = panes.get(key);
      const pkg = getPkg(extId); if (!pkg) return null;
      if (!e) {
        const frame = document.createElement('iframe');
        frame.setAttribute('sandbox', 'allow-scripts'); frame.setAttribute('referrerpolicy', 'no-referrer'); frame.setAttribute('title', paneDef.label); frame.className = 'extframe';
        frame.srcdoc = paneDocument(pkg.files, paneDef.file);
        e = { key, extId, paneId: paneDef.id, frame }; panes.set(key, e);
      }
      if (e.frame.parentNode !== container) container.append(e.frame);
      return e.frame;
    },
    post(extId, paneId, message) { const e = panes.get(`${extId}/${paneId}`); if (e) reply(e, { t: 'emit', e: 'message', v: message }); },
    theme() { for (const e of panes.values()) reply(e, { t: 'emit', e: 'theme', v: getTheme() }); },
    /** Drop every iframe of a package (removed, disabled, replaced). */
    drop(extId) { for (const [k, e] of panes) if (e.extId === extId) { e.frame.remove(); panes.delete(k); } },
    has: (extId, paneId) => panes.has(`${extId}/${paneId}`),
  };
}
